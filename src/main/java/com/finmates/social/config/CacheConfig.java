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
     * Caffeine-backed cache manager with named caches.
     *
     * <ul>
     *   <li>{@code userCache} — user info fetched from finmates-main; 5 min TTL, max 10 000 entries</li>
     *   <li>{@code profileCache} — social profile data; 2 min TTL, max 10 000 entries</li>
     *   <li>{@code followGraphCache} — follower ID lists used for feed fan-out; 5 min TTL, max 10 000</li>
     *   <li>{@code internal-follows} — CP5(c) belt-and-suspenders cache for the four
     *       {@code /api/internal/follows/*} endpoints; 60 s TTL, max 10 000. Aligns with the
     *       main-side {@code FmSocialClient} TTL (cp5-c-design.md §2e). Distinct key prefixes
     *       are used per endpoint to avoid collision since all four endpoints share this cache.</li>
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
        manager.registerCustomCache("internal-follows",
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(10_000)
                        .build());
        return manager;
    }
}
