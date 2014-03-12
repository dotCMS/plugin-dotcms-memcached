This plugin will override the standard dotcms caching infrastructure and replaces it
with Memcached.  Memcached needs to be configured externally to dotcms.

You point to a memcached port by using the [dotmarketing-config.properties](https://github.com/dotCMS/plugin-dotcms-memcached/blob/master/conf/dotmarketing-config-ext.properties) 

The library used in this implementation is called XMemcached.  You can read more about it
here:

https://code.google.com/p/xmemcached/

Hopefully, the config is self documenting.

One nice feature of this cache is that memcached servers can be added or removed while
the server is running.  The code for doing so looks like this:

```
MemcachedCacheAdministratorImpl mem = (MemcachedCacheAdministratorImpl) CacheLocator.getCacheAdministrator().getImplementationObject();
mem.addServer("127.0.0.1:11212", 5);
mem.removeServer("127.0.0.1:11212");
```
