package com.aicopilot.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisCacheService cacheService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new RedisCacheService(redisTemplate);
    }

    @Test
    void storesValueWithTtl() {
        Duration ttl = Duration.ofMinutes(5);

        cacheService.put("user:1", "Alice", ttl);

        verify(valueOperations).set("user:1", "Alice", ttl);
    }

    @Test
    void returnsCachedValue() {
        when(valueOperations.get("user:1")).thenReturn("Alice");

        Optional<String> result = cacheService.get("user:1");

        assertThat(result).contains("Alice");
    }

    @Test
    void evictsValue() {
        cacheService.evict("user:1");

        verify(redisTemplate).delete("user:1");
    }

    @Test
    void incrementsCounterAndSetsTtlOnFirstUse() {
        when(valueOperations.increment("rate-limit:login:a@b.com")).thenReturn(1L);

        long count = cacheService.increment("rate-limit:login:a@b.com", Duration.ofMinutes(15));

        assertThat(count).isEqualTo(1);
        verify(redisTemplate).expire("rate-limit:login:a@b.com", Duration.ofMinutes(15));
    }

    @Test
    void doesNotRefreshTtlOnLaterIncrements() {
        when(valueOperations.increment("rate-limit:login:a@b.com")).thenReturn(2L);

        cacheService.increment("rate-limit:login:a@b.com", Duration.ofMinutes(15));

        // 关键断言：只在第一次挂过期时间。
        // 如果每次自增都 expire，窗口会被持续访问不断续命、永远不归零，
        // 限流就从「临时拦截」变成了「永久封禁」——这是限流器最经典的翻车方式。
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void failsLoudlyWhenRedisReturnsNoIncrementResult() {
        when(valueOperations.increment("rate-limit:login:a@b.com")).thenReturn(null);

        assertThatThrownBy(() -> cacheService.increment("rate-limit:login:a@b.com", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }
}
