package com.finmates.social.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * In-process cache for cross-service user lookups (finmates-main).
 *
 * fm-social only uses main for auth-state fields (emailVerified, isActive).
 * Profile display data (avatar, bio, name) comes from social.profiles directly.
 *
 * Cache TTL: 5 minutes. Falls back to Optional.empty() on error.
 *
 * NOTE: fetchByUsername calls GET /api/internal/users/by-username/{username}/summary
 * which needs to be added to finmates-main InternalController. Until it exists,
 * getByUsername() will return Optional.empty() gracefully (main returns 404/500).
 */
@Component
@Slf4j
public class UserLookupCache {

    private final WebClient mainClient;

    /** userId → UserSummary */
    private final Cache<Long, UserSummary> byId = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    /** username (lowercase) → UserSummary */
    private final Cache<String, UserSummary> byUsername = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public UserLookupCache(@Qualifier("mainServiceWebClient") WebClient mainClient) {
        this.mainClient = mainClient;
    }

    /**
     * Looks up a user by numeric ID. Returns empty on cache miss + main down.
     */
    public Optional<UserSummary> getByUserId(Long userId) {
        UserSummary cached = byId.getIfPresent(userId);
        if (cached != null) {
            log.debug("UserLookupCache hit for userId={}", userId);
            return Optional.of(cached);
        }
        return fetchById(userId);
    }

    /**
     * Looks up a user by username. Returns empty on cache miss + main down.
     * Requires GET /api/internal/users/by-username/{username}/summary in finmates-main.
     */
    public Optional<UserSummary> getByUsername(String username) {
        UserSummary cached = byUsername.getIfPresent(username.toLowerCase());
        if (cached != null) {
            log.debug("UserLookupCache hit for username={}", username);
            return Optional.of(cached);
        }
        return fetchByUsername(username);
    }

    public void invalidate(Long userId) {
        byId.invalidate(userId);
    }

    @SuppressWarnings("unchecked")
    private Optional<UserSummary> fetchById(Long userId) {
        try {
            var response = mainClient.get()
                    .uri("/api/internal/users/{id}/summary", userId)
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            if (response == null) return Optional.empty();
            UserSummary summary = fromMap(response);
            byId.put(userId, summary);
            if (summary.username() != null) {
                byUsername.put(summary.username().toLowerCase(), summary);
            }
            return Optional.of(summary);
        } catch (Exception e) {
            log.warn("UserLookupCache: main service unavailable for userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<UserSummary> fetchByUsername(String username) {
        try {
            var response = mainClient.get()
                    .uri("/api/internal/users/by-username/{username}/summary", username)
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            if (response == null) return Optional.empty();
            UserSummary summary = fromMap(response);
            byUsername.put(username.toLowerCase(), summary);
            if (summary.userId() != null) {
                byId.put(summary.userId(), summary);
            }
            return Optional.of(summary);
        } catch (Exception e) {
            log.warn("UserLookupCache: main service unavailable for username={}: {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private UserSummary fromMap(java.util.Map<?, ?> map) {
        Long userId = map.get("userId") instanceof Number n ? n.longValue() : null;
        String username = (String) map.get("username");
        Boolean emailVerified = map.get("emailVerified") instanceof Boolean b ? b : false;
        Boolean isActive = map.get("isActive") instanceof Boolean b ? b : true;
        return new UserSummary(userId, username, emailVerified, isActive);
    }

    /**
     * Minimal user summary from finmates-main — auth state only.
     * Profile display fields (avatar, bio) come from social.profiles directly.
     */
    public record UserSummary(
            Long userId,
            String username,
            boolean emailVerified,
            boolean isActive
    ) {}
}
