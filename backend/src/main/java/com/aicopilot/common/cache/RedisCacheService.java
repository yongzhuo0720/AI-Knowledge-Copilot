package com.aicopilot.common.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate redisTemplate;

    public RedisCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value == null) {
            throw new IllegalStateException("redis returned no increment result for key " + key);
        }
        // 只在计数器刚被创建时下过期时间。
        // 如果每次自增都 expire，窗口就会被不断续命：攻击者只要一直发请求，
        // 计数永远不归零，限流就从「临时拦截」变成了「永久封禁」——这是很常见的一个坑。
        if (value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return value;
    }
}
