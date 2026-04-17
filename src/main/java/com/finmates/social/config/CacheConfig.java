package com.finmates.social.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * Caffeine-backed cache manager with three named caches.
     *
     * <ul>
     *   <li>{@code userCache} — user info fetched from finmates-main; 5 min TTL, max 10 000 entries</li>
     *   <li>{@code profileCache} — social profile data; 2 min TTL, max 10 000 entries</li>
     *   <li>{@code followGraphCache} — follower ID lists used for feed fan-out; 5 min TTL, max 10 000</li>
     * </ul>
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache("userCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache("profileCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(2, TimeUnit.MINUTES)
                        .maximumSize(10_000)
                        .build());
        manager.registerCustomCache("followGraphCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(10_000)
                        .build());
        return manager;
    }
}
