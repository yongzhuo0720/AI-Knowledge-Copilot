package com.aicopilot.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void evict(String key);

    /**
     * 在 {@code ttl} 窗口内**原子**自增计数器，返回自增后的值。
     *
     * <p>为什么单独要一个方法，而不是「get 出来 +1 再 put 回去」：
     * 那种写法是「读-改-写」三步，两个并发请求可能同时读到 3，各自写回 4，
     * 于是两次尝试只被记成一次。限流器最怕的就是在临界点上少数——攻击者
     * 只要并发发请求就能把 5 次的上限放大成 50 次。Redis 的 INCR 是单线程
     * 原子操作，天然没有这个问题。
     *
     * <p>过期时间只在**第一次**写入时设置，所以窗口是固定窗口（fixed window），
     * 不会因为持续访问而被不断续命。
     */
    long increment(String key, Duration ttl);
}
