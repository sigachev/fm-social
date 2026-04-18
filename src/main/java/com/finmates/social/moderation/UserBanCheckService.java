package com.finmates.social.moderation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Checks whether a user is currently banned by querying finmates-main's internal
 * ban-status endpoint. Results are cached for 60 seconds to avoid per-request
 * cross-service calls on every post/comment submission.
 *
 * <p>Cache key: userId. Value: true = banned (PERMANENT or active SUSPENSION), false = not banned.
 *
 * <p>If the ban-status endpoint is unreachable, the check is skipped (fail-open) to
 * prevent ban-service outages from blocking normal social activity.
 */
@Slf4j
@Service
public class UserBanCheckService {

    private static final String BAN_STATUS_PATH = "/api/internal/users/{userId}/ban-status";

    private final WebClient mainWebClient;

    @Value("${finmates.internal.shared-secret}")
    private String internalSharedSecret;

    /** Cache: userId → isBanned. 60s TTL, max 5000 entries. */
    private final Cache<Long, Boolean> banCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    public UserBanCheckService(WebClient mainServiceWebClient) {
        this.mainWebClient = mainServiceWebClient;
    }

    /**
     * Throws {@code 403 FORBIDDEN} if the user has an active ban.
     * Skips the check (fail-open) on network or timeout errors.
     */
    public void assertNotBanned(Long userId) {
        Boolean cached = banCache.getIfPresent(userId);
        if (cached != null) {
            if (cached) throw bannedException();
            return;
        }

        try {
            BanStatusResponse response = mainWebClient.get()
                    .uri(BAN_STATUS_PATH, userId)
                    .header("X-Internal-Secret", internalSharedSecret)
                    .retrieve()
                    .bodyToMono(BanStatusResponse.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            boolean isBanned = response != null && response.banned();
            banCache.put(userId, isBanned);

            if (isBanned) throw bannedException();

        } catch (ResponseStatusException e) {
            throw e; // re-throw our own 403
        } catch (WebClientResponseException e) {
            log.warn("Ban-status check returned {} for userId {}, skipping check", e.getStatusCode(), userId);
        } catch (Exception e) {
            log.warn("Ban-status check failed for userId {} ({}), skipping check", userId, e.getMessage());
        }
    }

    /** Invalidates the cached ban state for a user (e.g., after an unban). */
    public void invalidate(Long userId) {
        banCache.invalidate(userId);
    }

    private ResponseStatusException bannedException() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Your account has been suspended or banned");
    }

    /** DTO matching finmates-main's BanStatusResponse. */
    record BanStatusResponse(boolean banned, String banType, String expiresAt) {}
}
