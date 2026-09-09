package com.aicopilot.common.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    void put(String key, String value, Duration ttl);

    Optional<String> get(String key);

    void evict(String key);
}
