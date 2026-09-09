package com.aicopilot.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
