package com.finmates.social.moderation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
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
 * <p>Cache key: userId. Value: full BanStatusResponse — use banned() to check state.
 * A sentinel value with banned=false is stored on cache miss so we don't re-fetch for active users.
 *
 * <p>If the ban-status endpoint is unreachable, the check is skipped (fail-open) to
 * prevent ban-service outages from blocking normal social activity.
 */
@Slf4j
@Service
public class UserBanCheckService {

    private static final String BAN_STATUS_PATH = "/api/internal/users/{userId}/ban-status";

    /** Sentinel stored for users confirmed NOT banned — avoids re-fetching on every request. */
    private static final BanStatusResponse NOT_BANNED = new BanStatusResponse(false, null, null);

    private final WebClient mainWebClient;

    @Value("${finmates.internal.shared-secret}")
    private String internalSharedSecret;

    /** Cache: userId → BanStatusResponse. 60s TTL, max 5000 entries. */
    private final Cache<Long, BanStatusResponse> banCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(5_000)
            .build();

    public UserBanCheckService(WebClient mainServiceWebClient) {
        this.mainWebClient = mainServiceWebClient;
    }

    /**
     * Throws {@code 403 FORBIDDEN} (with structured error body) if the user has an active ban.
     * Skips the check (fail-open) on network or timeout errors.
     */
    public void assertNotBanned(Long userId) {
        BanStatusResponse cached = banCache.getIfPresent(userId);
        if (cached != null) {
            if (cached.banned()) throw bannedException(cached);
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

            BanStatusResponse state = (response != null && response.banned()) ? response : NOT_BANNED;
            banCache.put(userId, state);

            if (state.banned()) throw bannedException(state);

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

    /**
     * Builds a 403 response with a structured ProblemDetail body so the frontend
     * AxiosClient can detect {@code error: "USER_BANNED"} and redirect to /banned.
     */
    private ErrorResponseException bannedException(BanStatusResponse ban) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your account has been suspended or banned");
        problem.setProperty("error", "USER_BANNED");
        problem.setProperty("banType", ban.banType());
        problem.setProperty("expiresAt", ban.expiresAt());
        return new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
    }

    /** DTO matching finmates-main's BanStatusResponse. */
    record BanStatusResponse(boolean banned, String banType, String expiresAt) {}
}
