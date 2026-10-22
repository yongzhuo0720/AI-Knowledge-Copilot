package com.aicopilot.common.cache;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用的内存版 {@link CacheService}。
 *
 * <p>为什么需要它：限流是**有状态**的逻辑 —— 它的正确性恰恰体现在「第 5 次和第 6 次
 * 行为不一样」。用 Mockito mock 掉之后再 `thenReturn(1, 2, 3)` 硬编返回值，
 * 测的就变成「我预设的数列」而不是「计数逻辑本身」了，等于没测。
 * 所以这里给一个真会累加的实现。
 *
 * <p>它**不实现 TTL**（不做定时清理）：目前所有用例都在同一个瞬时窗口内跑完，
 * 要测「窗口过期」得引入可控时钟，那是另一件事。这一点是有意留白，不是漏了。
 */
public class InMemoryCacheService implements CacheService {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Long> counters = new HashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        values.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key))
                .or(() -> Optional.ofNullable(counters.get(key)).map(String::valueOf));
    }

    @Override
    public void evict(String key) {
        values.remove(key);
        counters.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        // 真实实现走 Redis 的原子 INCR；单线程测试里 HashMap.merge 语义等价。
        return counters.merge(key, 1L, Long::sum);
    }

    /** 测试辅助：直接指定某个计数器的当前值，省掉循环写几百行。 */
    public void seedCounter(String key, long value) {
        counters.put(key, value);
    }
}
