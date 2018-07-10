package com.netty.flash.ex11;

/**
 * Netty设计模式的应用:
 * 策略模式:
 * 1.封装一系列可相互替换的算法家族
 * 2.动态选择某一个策略
 * 应用范例:
 * @see io.netty.util.concurrent.DefaultEventExecutorChooserFactory#newChooser(io.netty.util.concurrent.EventExecutor[])
 * 算法家族:EventExecutorChooser,算法实现:next(),策略实现:PowerOfTowEventExecutorChooser/GenericEventExecutorChooser,选择策略:newChooser()
 */
public class Strategy {
    private Cache cacheMemory = new CacheMemoryImpl();
    private Cache cacheRedis = new CacheRedisImpl();

    public interface Cache {
        boolean add(String key, Object object);
    }

    public class CacheMemoryImpl implements Cache {
        @Override
        public boolean add(String key, Object object) {
            // 保存到map
            return false;
        }
    }

    public class CacheRedisImpl implements Cache {
        @Override
        public boolean add(String key, Object object) {
            // 保存到redis
            return false;
        }
    }

    /**
     * 路由策略动态选择策略
     *
     * @param key
     * @return
     */
    public Cache getCache(String key) {
        if (key.length() < 10) {
            return cacheRedis;
        }
        return cacheMemory;
    }
}