This plugin will override the internal dotcms caching infrastructure (Guava) and replace it
with memcached.  The benifits of this are that with memcached, the caching infrastructure is networked and can be clustered and dynamically added to in order to add cache capacity.  It also gets the cache out of the java heap, which should speed up GC significantly.

see: http://memcached.org/

Memcached needs to be configured externally to dotcms.

You point to a memcached server/port by using the [dotmarketing-config.properties](https://github.com/dotCMS/plugin-dotcms-memcached/blob/master/conf/dotmarketing-config-ext.properties) 

The library used in this implementation is called XMemcached.  You can read more about it
here:

https://code.google.com/p/xmemcached/

Hopefully, the [config](https://github.com/dotCMS/plugin-dotcms-memcached/blob/master/conf/dotmarketing-config-ext.properties) is self documenting.


To test, fire up a memcached server locally, on port 11211 (should be the default):
```
memcached -vv
```

Then deploy this plugin, start dotcms and volia, you should see cache messages scrolling on your
memcached server.  To run memcached as a Deamon, you need to pass it the -d option
```
memcached -d
```

One nice feature of this cache is that memcached servers can be added or removed while
the dotcms servers are running.  The code for doing so looks like this:

```
MemcachedCacheAdministratorImpl mem = (MemcachedCacheAdministratorImpl) CacheLocator.getCacheAdministrator().getImplementationObject();
mem.addServer("127.0.0.1:11212", 5);
mem.removeServer("127.0.0.1:11212");
```
