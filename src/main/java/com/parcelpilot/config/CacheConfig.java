package com.parcelpilot.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * Repeat page loads must never burn API quota.
 * TTLs per spec: tracking 3h, weather 1h. Geocoding is cached permanently
 * inside OpenMeteoClient itself.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        Map<String, Cache> caches = Map.of(
                "tracking", new CaffeineCache("tracking",
                        Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(3)).maximumSize(2_000).build()),
                "weather", new CaffeineCache("weather",
                        Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(2_000).build()));

        return new CacheManager() {
            @Override public Cache getCache(String name) { return caches.get(name); }
            @Override public Collection<String> getCacheNames() { return caches.keySet(); }
        };
    }
}
