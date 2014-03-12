# Dotcms & Memcached

This plugin will override the internal dotcms caching infrastructure (Guava) and replace it with memcached, an external caching mechanism.  The benifits of memcached are multiple:
* the caching infrastructure becomes a networked server itself 
* the caching server can be a cluster of servers and can be scaled and added to in order to add cache capacity.  
* It  gets the Dotcms cache out of the java heap, which should speed up GC significantly.
* Cache puts and invalidations are network wide - there is no external syncing mechanism.
* Dotcms servers can be restarted with their caches fully loaded
* Amazon offers a cloud based memcache implementation called [Amazon ElastiCache](http://aws.amazon.com/elasticache) that can scale your cache in the cloud

# About Memcached
Memcached needs to be configured externally to dotcms and there is a lot to learn in running a memcached server.  For more information, see: http://memcached.org



# Configuring
You point to a memcached server/port by using the [dotmarketing-config.properties](https://github.com/dotCMS/plugin-dotcms-memcached/blob/master/conf/dotmarketing-config-ext.properties) 

Hopefully, the [config](https://github.com/dotCMS/plugin-dotcms-memcached/blob/master/conf/dotmarketing-config-ext.properties) is self documenting.


# Testing
To test, fire up a memcached server locally, on port 11211 (should be the default):
```
memcached -vv
```

Then deploy this plugin, start dotcms and volia, you should see cache messages scrolling on your
memcached server.  To run memcached as a Deamon, you need to pass it the -d option
```
memcached -d
```


# Library

The library used in this implementation is called XMemcached.  You can read more about it
here:

https://code.google.com/p/xmemcached/


# Adding and removing servers from the memcached cluster
The code for adding or removing servers from the memcached cluster looks like this:

```
MemcachedCacheAdministratorImpl mem = (MemcachedCacheAdministratorImpl) CacheLocator.getCacheAdministrator().getImplementationObject();
mem.addServer("127.0.0.1:11212", 5);
mem.removeServer("127.0.0.1:11212");
```
