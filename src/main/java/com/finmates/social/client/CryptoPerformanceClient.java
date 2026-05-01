package com.finmates.social.client;

import com.finmates.social.feed.dto.TraderPerf;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * fm-social-side client for finmates-crypto's per-user rolling-returns
 * endpoint. Two-layer cache by design:
 *
 * <ol>
 *   <li><b>Local Caffeine, 5 min TTL</b> here in fm-social — protects fm-social
 *       from re-querying crypto for repeated traders within the same browse
 *       session and acts as a freshness floor (a user who unfollowed mid-day
 *       still sees not-too-stale numbers).</li>
 *   <li><b>Source-of-truth, 24 h TTL</b> in finmates-crypto's
 *       {@code rollingReturns} cache (see {@code RollingReturnsCacheConfig}
 *       and {@code RollingReturnsCacheEvictionJob} on the crypto side).
 *       That's where the actual cost-saving cache lives — rolling returns
 *       only change once a day when the snapshot job runs at 00:00 UTC.</li>
 * </ol>
 *
 * <p>The two TTLs are intentionally different. Don't try to "synchronize" them:
 * the short fm-social TTL is a freshness floor, the long crypto TTL is the
 * cost-saving cache. If they were the same, fm-social would re-query crypto on
 * every cold local-cache hit even when crypto already has the value cached
 * — pointless network round-trip — but if fm-social cached as long as crypto,
 * a cleared crypto cache would still be masked by fm-social's stale entries.
 *
 * <p><b>Pattern</b>: mirrors {@link UserLookupCache} (manual Caffeine cache,
 * direct WebClient via {@code @Qualifier("cryptoServiceWebClient")},
 * outage-returns-empty contract). Same cache-then-fetch-on-miss flow, same
 * 3-second timeout, same try/catch-and-log error handling.
 */
@Component
@Slf4j
public class CryptoPerformanceClient {

    /** 5-minute freshness floor for the fm-social-side cache. */
    static final Duration TTL = Duration.ofMinutes(5);

    /** 3-second per-call timeout on the cross-service request — same cap as {@link UserLookupCache}. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Concurrency cap for {@link #getRollingReturnsBatch(Collection)} fan-out.
     * 8 in-flight requests is comfortable headroom for a feed page (limit≤50,
     * usually ≤20 distinct users) without thundering-herd risk on a cold
     * crypto-side cache.
     */
    private static final int BATCH_CONCURRENCY = 8;

    private final WebClient cryptoClient;

    private final Cache<Long, TraderPerf> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(50_000)
            .recordStats()
            .build();

    public CryptoPerformanceClient(@Qualifier("cryptoServiceWebClient") WebClient cryptoClient) {
        this.cryptoClient = cryptoClient;
    }

    /**
     * Fetches rolling returns for one user. Cache-first; on miss, makes a
     * single cross-service call. Returns {@link TraderPerf#EMPTY} on outage —
     * never throws, never blocks beyond {@link #CALL_TIMEOUT}. The empty
     * sentinel is also <b>cached</b> so a downed crypto service doesn't trigger
     * a thundering herd of doomed retries within the TTL window.
     */
    public TraderPerf getRollingReturns(Long userId) {
        TraderPerf cached = cache.getIfPresent(userId);
        if (cached != null) {
            log.debug("CryptoPerformanceClient hit for userId={}", userId);
            return cached;
        }
        TraderPerf fresh = fetchOne(userId);
        cache.put(userId, fresh);
        return fresh;
    }

    /**
     * Batch variant — one round trip per cache-miss user, fanned out
     * concurrently up to {@link #BATCH_CONCURRENCY}. A feed page that
     * surfaces 20 events from 20 distinct traders pays at most 20 cross-service
     * calls cold (then 0 within the next 5 min as the local cache warms);
     * the source-of-truth cache on the crypto side absorbs the actual SQL cost
     * for 24 h.
     *
     * <p>Returns a map keyed by every input userId (cache-misses default to
     * {@link TraderPerf#EMPTY}). Order of input is not preserved — callers
     * look up by id.
     */
    public Map<Long, TraderPerf> getRollingReturnsBatch(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();

        Map<Long, TraderPerf> result = new LinkedHashMap<>();
        Set<Long> misses = new HashSet<>();

        for (Long id : userIds) {
            if (id == null) continue;
            TraderPerf hit = cache.getIfPresent(id);
            if (hit != null) {
                result.put(id, hit);
            } else {
                misses.add(id);
            }
        }
        if (misses.isEmpty()) return result;

        // Fan out — bounded concurrency, never blocks the calling thread
        // beyond the slowest of the batch's CALL_TIMEOUTs.
        Map<Long, TraderPerf> fetched = Flux.fromIterable(misses)
                .flatMap(id -> Mono.fromCallable(() -> Map.entry(id, fetchOne(id)))
                                   .subscribeOn(Schedulers.boundedElastic()),
                        BATCH_CONCURRENCY)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block();

        if (fetched != null) {
            fetched.forEach((id, perf) -> {
                cache.put(id, perf);
                result.put(id, perf);
            });
        }

        // Defensive — if Reactor missed any (shouldn't happen, but keep the
        // contract that every input userId has an entry).
        for (Long id : misses) {
            result.putIfAbsent(id, TraderPerf.EMPTY);
        }
        return result;
    }

    /** Test-only — invalidate to force a refetch in cache-expiration tests. */
    public void invalidate(Long userId) {
        cache.invalidate(userId);
    }

    /** Test-only — cache-hit count via Caffeine stats. */
    public long cacheHitCount() {
        return cache.stats().hitCount();
    }

    /** Test-only — cache-miss count via Caffeine stats. */
    public long cacheMissCount() {
        return cache.stats().missCount();
    }

    private TraderPerf fetchOne(Long userId) {
        try {
            TraderPerf result = cryptoClient.get()
                    .uri("/api/internal/users/{id}/rolling-returns", userId)
                    .retrieve()
                    .bodyToMono(TraderPerf.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            return result != null ? result : TraderPerf.EMPTY;
        } catch (Exception e) {
            log.warn("CryptoPerformanceClient: rolling-returns fetch failed for userId={}: {}",
                    userId, e.getMessage());
            return TraderPerf.EMPTY;
        }
    }

    /** Internal — clear the entire cache. Test-only escape hatch. */
    void clearCache() {
        cache.invalidateAll();
    }

    // Convenience map builder used by tests
    static Map<Long, TraderPerf> singletonMap(Long id, TraderPerf p) {
        Map<Long, TraderPerf> m = new HashMap<>();
        m.put(id, p);
        return m;
    }
}
