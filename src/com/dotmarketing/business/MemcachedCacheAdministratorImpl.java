/**
 *
 */
package com.dotmarketing.business;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.MemcachedClientBuilder;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.utils.AddrUtil;

import org.apache.commons.codec.digest.DigestUtils;
import org.jgroups.JChannel;

import com.dotcms.enterprise.LicenseUtil;
import com.dotmarketing.business.DotCacheAdministrator;
import com.dotmarketing.business.RoleCache.UserRoleCacheHelper;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;

public class MemcachedCacheAdministratorImpl implements DotCacheAdministrator {
	MemcachedClient _client = null;
	DotGuavaCacheAdministratorImpl _oldCache = null;

	List<InetSocketAddress> _servers = new ArrayList<InetSocketAddress>();

	String[] _ignoreRegion = Config.getStringArrayProperty("memcached.ignore.regions");

	List<Integer> _weights = new ArrayList<Integer>();
	Map<String, Integer> _expires = new HashMap<String, Integer>();

	String _serverId = Config.getStringProperty("DIST_INDEXATION_SERVER_ID", "1");
	String[] _allServerIds = Config.getStringArrayProperty("DIST_INDEXATION_SERVERS_IDS");
	int _errorLogTimeout = Config.getIntProperty("memcached.error.log.every.ms", 1000);

	public MemcachedCacheAdministratorImpl() {

		// set the default for ignored regions
		if (_ignoreRegion == null || _ignoreRegion.length < 1) {
			String[] x = { "velocitymenucache", "indiciescacheinfo" };
			this._ignoreRegion = x;
		}
		Arrays.sort(_ignoreRegion);

		// set default region expiry
		_expires.put("default", Config.getIntProperty("memcached.default.expires", 0));

		Iterator<String> it = Config.getKeys();

		// get the config
		while (it.hasNext()) {
			String key = it.next();
			if (key == null) {
				continue;
			}

			// allow an expirey date for each cache region
			if (key.startsWith("memcached.") && key.endsWith(".expires")) {
				String cacheName = key.split("\\.")[1];
				int exp = Config.getIntProperty(key, Config.getIntProperty("memcached.default.expires", 0));
				Map<String, Integer> m = new HashMap<String, Integer>();
				m.put(cacheName.toLowerCase(), exp);
				_expires.putAll(m);
				Logger.info(this.getClass(), "***\t Memcached Config expires: " + cacheName + ": " + exp);
				// allow an server weight for each server
			} else if (key.startsWith("memcached.server.") && key.endsWith(".address")) {

				try {
					int x = Integer.parseInt(key.split("\\.")[2]);
					String address = Config.getStringProperty(key);
					InetSocketAddress addr = AddrUtil.getOneAddress(address);
					_servers.add(addr);
				} catch (Exception ex) {
					Logger.error(this.getClass(),
							"***\t Memcached Config server address error: " + Config.getStringProperty(key));
					Logger.error(this.getClass(), "***\t Memcached Config server address error: " + ex.getMessage());

				}

				Logger.info(this.getClass(), "***\t Memcached Config server: " + Config.getStringProperty(key));
			} else if (key.startsWith("memcached.server") && key.endsWith("weight")) {
				int x = Integer.parseInt(key.split("\\.")[2]);
				_weights.add(Config.getIntProperty(key, 1));
				Logger.info(this.getClass(), "***\t Memcached Config server weight: " + Config.getIntProperty(key, 1));
			}

		}

		if (_servers.size() == 0) {
			InetSocketAddress addr = AddrUtil.getOneAddress(Config.getStringProperty("memcached.server.address.default",
					"localhost:11211"));

			_servers.add(addr);
		}
		if(LicenseUtil.getLevel()<300){
			_allServerIds = new String[0];
		}

		_oldCache = new DotGuavaCacheAdministratorImpl();

	}

	public void addServer(String inetAddress, int weight) {
		synchronized (this) {
			Logger.info(this.getClass(), "***\t Memcached Config adding server:" + inetAddress + " " + weight);

			boolean reset = true;

			try {
				List<InetSocketAddress> newServers = new ArrayList<InetSocketAddress>();
				List<Integer> newWeights = new ArrayList<Integer>();
				InetSocketAddress addr = AddrUtil.getOneAddress(inetAddress);
				newServers.add(addr);
				newWeights.add(weight);

				for (int i = 0; i < _servers.size(); i++) {
					InetSocketAddress ip = _servers.get(i);
					if (ip.equals(addr) || ip.toString().endsWith(addr.toString())) {
						reset = false;
					} else {
						newWeights.add(_weights.get(i));
						newServers.add(ip);
					}
				}

				if (reset) {
					MemcachedClient x = getClient();
					this._servers = newServers;
					this._weights = newWeights;
					this._client = null;
					x.shutdown();
					x = null;
				}
			} catch (Exception e) {
				Logger.error(this.getClass(), "No memcache client can be built:" + e.getMessage(), e);
			}
		}
	}

	public void removeServer(String inetAddress) {
		synchronized (this) {
			try {
				Logger.info(this.getClass(), "***\t Memcached Config removing server:" + inetAddress);
				boolean reset = false;

				List<InetSocketAddress> newServers = new ArrayList<InetSocketAddress>();
				List<Integer> newWeights = new ArrayList<Integer>();
				InetSocketAddress addr = AddrUtil.getOneAddress(inetAddress);
				for (int i = 0; i < _servers.size(); i++) {
					InetSocketAddress ip = _servers.get(i);
					if (ip.equals(addr) || ip.toString().endsWith(addr.toString())) {
						reset = true;
					} else {
						newServers.add(ip);
						newWeights.add(_weights.get(i));
					}
				}
				if (reset) {
					MemcachedClient x = getClient();
					this._servers = newServers;
					this._weights = newWeights;
					this._client = null;
					x.shutdown();
					x = null;
				}
			} catch (Exception e) {
				Logger.error(this.getClass(), "No memcache client can be built:" + e.getMessage(), e);
			}
		}
	}

	private MemcachedClient getClient() throws MemcachedException {
		if (_client == null) {
			synchronized (this) {
				if (_client == null) {
					try {

						int[] runWeights = new int[_servers.size()];
						for (int i = 0; i < _servers.size(); i++) {
							try {
								runWeights[i] = (Integer) _weights.get(i);
							} catch (Exception e) {
								// default to weight:1
								runWeights[i] = 1;
							}
						}

						MemcachedClientBuilder builder = new XMemcachedClientBuilder(_servers, runWeights);
						builder.setEnableHealSession(true);
						builder.setConnectionPoolSize(Config.getIntProperty("memcached.connection.pool.size", 10));
						_client = builder.build();
						_client.setSanitizeKeys(true);
						_client.getTranscoder().setCompressionThreshold(
								Config.getIntProperty("memcached.compression.threshold", 1024));
						// try it to see if we get an error
						_client.get("TEST_TEST");

					} catch (Exception e) {
						Logger.error(this.getClass(), "No memcache client can be built:" + e.getMessage(), e);

						try {
							if (_client != null) {
								// _client.shutdown();
								// _client = null;
							}
						} catch (Exception ex) {
							Logger.debug(this.getClass(), "memcache client shutdown failed:" + ex.getMessage());
						}
						throw new MemcachedException("Unable to contact servers: " + e.getMessage());
					}

				}
			}
		}
		return _client;

	}

	@Override
	public Set<String> getKeys(String group) {
		group = (group != null) ? group.toLowerCase() : group;

		if (!canSerialize(group, group)) {
			return _oldCache.getKeys(group);
		}
		Logger.info(this.getClass(), "getKeys.group Not Implemented, too slow with Memcache:" + group);
		return new HashSet<String>();

	}

	@Override
	public void flushAll() {
		Logger.info(this.getClass(), "Full Cache Flush");
		try {
			_oldCache.flushAll();
			getClient().flushAll();

		} catch (TimeoutException e) {
			Logger.error(this.getClass(), "Flush all failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Logger.error(this.getClass(), "Flush all failed: " + e.getMessage(), e);
		} catch (MemcachedException e) {
			Logger.error(this.getClass(), "Flush all failed: " + e.getMessage(), e);
		}
	}

	@Override
	public void flushGroup(String group) {
		if(group == null) return;
		group=group.toLowerCase();
		if (!canSerialize(group, group)) {
			
			_oldCache.flushGroup(group);
			remove("indiciescacheinfo","indiciescache");
			return;
		}
		remove("indiciescacheinfo","indiciescache");
		Logger.debug(this.getClass(), "flushGroup Not Implemented, doing a flushAll instead" + group);
	}

	private boolean canSerialize(String key, String group) {

		if (Arrays.binarySearch(_ignoreRegion, group) > -1) {
			return false;
		}
		if (key.startsWith("velocitycache")) {
			if (!(key.contains("live") || key.contains("working"))) {
				return false;
			}
		} else if (key.endsWith(".vm")) {
			return false;
		}
		return true;
	}

	@Override
	public void flushAlLocalOnlyl() {
		_oldCache.flushAlLocalOnlyl();
		remove("indiciescacheinfo","indiciescache");
		Logger.debug(this.getClass(), "flushAlLocalOnlyl Not Implemented");
	}

	@Override
	public void flushGroupLocalOnly(String group) {
		if (!canSerialize(group, group)) {
			_oldCache.flushGroupLocalOnly(group);
			return;
		}

		Logger.debug(this.getClass(), "flushGroupLocalOnly Not Implemented:" + group);

	}

	@Override
	public Object get(String key, String group) throws DotCacheException {
		if (key == null || group == null) {
			return null;
		}
		key = key.toLowerCase();
		group = group.toLowerCase();

		try {
			String newKey = _sanitizeKey(key, group);

			Object o = null;

			if (!canSerialize(key, group)) {
				o = _getOldSchool(newKey, key, group);
			}
			if (o == null) {
				o = getClient().get(newKey);
				if ("dotcmsuserrolecache".equals(group) && o != null) {
					List<UserRoleCacheHelper> rlist = new ArrayList<UserRoleCacheHelper>();
					List<UserRoleCacheHelperMemcached> wrappers = (List<UserRoleCacheHelperMemcached>) o;
					for (UserRoleCacheHelperMemcached urc : wrappers) {
						rlist.add(new UserRoleCacheHelper(urc.getRoleId(), urc.isInherited()));
					}
					o = rlist;
				}
			}
			return o;
		} catch (MemcachedException e) {
			_logCacheError("get failed: " + e.getMessage(), e);
			Logger.debug(this.getClass(), "get failed: " + e.getMessage(), e);
		} catch (Exception e) {
			Logger.error(this.getClass(), "get failed: " + e.getMessage(), e);
		}
		return null;

	}

	private Object _getOldSchool(String newKey, String oldKey, String group) throws DotCacheException {

		Object obj = null;
		Object cacheGood = null;
		try {
			cacheGood = getClient().get(newKey + _serverId);
		} catch (MemcachedException e) {
			_logCacheError("_getOldSchool failed: " + e.getMessage(), e);
			Logger.debug(this.getClass(), "_getOldSchool failed: " + e.getMessage(), e);
			return null;
		} catch (Exception e) {
			Logger.error(this.getClass(), "_getOldSchool failed: " + e.getMessage(), e);
			return null;
		}

		// VelocityMacros can never be unloaded (unless we restart the velocity
		// engine)
		if (cacheGood != null || oldKey.endsWith(".vm")) {
			obj = _oldCache.get(oldKey, group);
		} else {
			_oldCache.remove(oldKey, group);
		}
		return obj;
	}

	@Override
	public void put(String key, Object content, String group) {

		if (key == null || group == null) {
			return;
		}
		key = key.toLowerCase();
		group = group.toLowerCase();

		try {

			String newKey = _sanitizeKey(key, group);
			if (!canSerialize(key, group)) {
				_putOldSchool(newKey, key, group, content);
			} else {
				if ("dotcmsuserrolecache".equals(group)) {
					List<UserRoleCacheHelper> con = (List<UserRoleCacheHelper>) content;
					List<UserRoleCacheHelperMemcached> rList = new ArrayList<UserRoleCacheHelperMemcached>();
					for (UserRoleCacheHelper urc : con) {
						rList.add(new UserRoleCacheHelperMemcached(urc.getRoleId(), urc.isInherited()));
					}
					content = rList;
				}

				getClient().add(newKey, _getExpires(group), content);
			}
		} catch (MemcachedException e) {
			_logCacheError("put failed: " + e.getMessage(), e);
			Logger.debug(this.getClass(), "put failed: " + e.getMessage(), e);
		} catch (Exception e) {
			Logger.error(this.getClass(), "put failed: " + e.getMessage());
		}

	}

	/**
	 * This method will put
	 * 
	 * @param newKey
	 * @param oldKey
	 * @param group
	 * @param content
	 * @throws TimeoutException
	 * @throws InterruptedException
	 * @throws MemcachedException
	 */
	private void _putOldSchool(String newKey, String oldKey, String group, Object content) {

		try {
			// put a placeholder for this server
			getClient().add(newKey + _serverId, _getExpires(group), new Boolean(true));
		} catch (MemcachedException e) {
			_logCacheError("_putOldSchool failed: " + e.getMessage(), e);
		} catch (Exception e) {
			Logger.error(this.getClass(), "_putOldSchool failed: " + e.getMessage());
		}
		_oldCache.put(oldKey, content, group);

		for (String x : _allServerIds) {
			if (!x.equals(_serverId)) {
				try {
					getClient().delete(newKey + x);
				} catch (MemcachedException e) {
					_logCacheError("_putOldSchool failed: " + e.getMessage(), e);

				} catch (Exception e) {
					Logger.error(this.getClass(), "_putOldSchool failed: " + e.getMessage());
				}

			}
		}
	}

	@Override
	public void remove(String key, String group) {
		if (key == null || group == null) {
			return;
		}
		key = key.toLowerCase();
		group = group.toLowerCase();
		String newKey = _sanitizeKey(key, group);
		try {

			getClient().deleteWithNoReply(newKey);

		} catch (MemcachedException e) {
			_logCacheError("remove failed: " + e.getMessage(), e);

		} catch (Exception e) {
			Logger.error(this.getClass(), "remove failed: " + e.getMessage(), e);
		}
		if (!canSerialize(key, group)) {

			_removeOldSchool(newKey, key, group);

		}
	}

	/**
	 * This method will remove from guava
	 * 
	 * @param newKey
	 * @param oldKey
	 * @param group
	 * @param content
	 * @throws TimeoutException
	 * @throws InterruptedException
	 * @throws MemcachedException
	 */
	private void _removeOldSchool(String newKey, String oldKey, String group) {

		_oldCache.remove(oldKey, group);
		try {
			getClient().deleteWithNoReply(newKey);
			for (String x : _allServerIds) {
				getClient().deleteWithNoReply(newKey + x);
			}
		} catch (MemcachedException e) {
			_logCacheError("_removeOldSchool failed: " + e.getMessage(), e);
		} catch (Exception e) {
			Logger.error(this.getClass(), "remove failed: " + e.getMessage(), e);
		}

	}

	private int _getExpires(String group) {
		Integer exp = _expires.get(group);
		if (exp == null) {
			exp = _expires.get("default");
		}
		return exp;

	}

	@Override
	public void removeLocalOnly(String key, String group) {
		if (!canSerialize(key, group)) {
			_oldCache.removeLocalOnly(key, group);
			return;
		}

		Logger.debug(this.getClass(), "removeLocalOnly Not Implemented, does a global remove only" + key + ":" + group);
		remove(key, group);
	}

	@Override
	public void shutdown() {
		try {
			_client.shutdown();
			_oldCache.shutdown();
		} catch (IOException e) {
			Logger.error(this.getClass(), "shutdown failed" + e.getMessage(), e);
		}

	}

	@Override
	public JChannel getJGroupsChannel() {

		return null;
	}

	@Override
	public List<Map<String, Object>> getCacheStatsList() {
		Logger.info(this.getClass(), "To Be Implemented");
		return _oldCache.getCacheStatsList();
	}

	private String _sanitizeKey(String key, String group) {
		return ((key.length() + group.length()) > 100) ? DigestUtils.md5Hex(key + group) : key + group;
	}

	@Override
	public Class getImplementationClass() {
		return this.getClass();
	}

	@Override
	public DotCacheAdministrator getImplementationObject() {
		return this;
	}

	static long _lastLog = System.currentTimeMillis();

	/**
	 * Log one error message a sec - otherwise we will kill the log file
	 * 
	 * @param message
	 * @param e
	 */
	private void _logCacheError(String message, Exception e) {

		if (_lastLog < System.currentTimeMillis()) {
			_lastLog = System.currentTimeMillis() + _errorLogTimeout;
			Logger.info(this.getClass(), message);
			Logger.debug(this.getClass(), message, e);
		}

	}

	/**
	 * This is a serializable wrapper for the UserRoleCacheHelper class, that
	 * should be serializable
	 * 
	 * @author will
	 * 
	 */
	protected static class UserRoleCacheHelperMemcached implements Serializable {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		private String roleId;
		private boolean inherited;

		protected UserRoleCacheHelperMemcached(String roleId, boolean inherited) {

			this.roleId = roleId;
			this.inherited = inherited;
		}

		/**
		 * @return the roleId
		 */
		protected String getRoleId() {
			return roleId;
		}

		/**
		 * @return the inherited
		 */
		protected boolean isInherited() {
			return inherited;
		}
	}

}
