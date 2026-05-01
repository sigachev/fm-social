package com.finmates.social.feed;

import com.finmates.social.client.CryptoPerformanceClient;
import com.finmates.social.client.UserLookupCache;
import com.finmates.social.feed.dto.FollowingActivityEvent;
import com.finmates.social.feed.dto.InternalTradeDto;
import com.finmates.social.feed.dto.TraderPerf;
import com.finmates.social.follow.FollowRepository;
import com.finmates.social.profile.ProfileService;
import com.finmates.social.profile.dto.ProfileSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the "Following Activity" feed surfaced at
 * {@code GET /api/feed/following}.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Resolve followed user IDs from {@link FollowRepository#findAllFollowingIds}.</li>
 *   <li>Cross-service call: {@code GET /api/internal/trades/recent?userIds=…&limit=N×2}
 *       on finmates-crypto. We request <b>2× the page limit</b> because each
 *       closed trade expands into both an OPENED and a CLOSED event, so a page
 *       of {@code limit=20} could need up to 40 underlying trades worth of
 *       events to fill (worst case: every trade is closed). Capped at the
 *       crypto-side max of 200.</li>
 *   <li>Expand each {@link InternalTradeDto} into 1 (open) or 2 (closed)
 *       {@link FollowingActivityEvent} rows. Closed trades emit both halves.</li>
 *   <li>Sort all events by {@code occurredAt DESC}, take {@code limit}.</li>
 *   <li>Hydrate each event with {@code username + avatarUrl} from
 *       {@link ProfileService#getBatchSummaries(List)} (one batch call) and
 *       with {@code traderPerf} from
 *       {@link CryptoPerformanceClient#getRollingReturnsBatch} (one batch
 *       fan-out, see that method's javadoc for the cache-then-fanout shape).</li>
 * </ol>
 *
 * <p><b>Per-call cost ceiling</b>: 1 follow-graph query + 1 trades fetch +
 * 1 profile-batch + 1 perf-batch = 4 cross-service or DB round trips per feed
 * page, regardless of {@code limit}. The perf-batch fans out internally but
 * only for cache-misses; with a warm fm-social cache (5 min TTL) and a warm
 * crypto-side cache (24 h TTL), steady-state is 3 round trips and a single
 * cheap O(1) Caffeine lookup per event.
 */
@Service
@Slf4j
public class FollowingFeedService {

    /** Default page size when no {@code limit} param is supplied. */
    public static final int DEFAULT_LIMIT = 20;

    /** Hard cap on {@code limit} — server-side clamp. */
    public static final int MAX_LIMIT = 50;

    /** Hard cap on the upstream trades-fetch — matches finmates-crypto's own ceiling. */
    private static final int CRYPTO_TRADES_MAX = 200;

    /** Network timeout for the trades-fetch — kept to 5 s so a slow crypto doesn't stall the whole feed. */
    private static final Duration TRADES_TIMEOUT = Duration.ofSeconds(5);

    private final FollowRepository followRepository;
    private final ProfileService profileService;
    private final UserLookupCache userLookupCache;
    private final CryptoPerformanceClient performanceClient;
    private final WebClient cryptoClient;

    public FollowingFeedService(FollowRepository followRepository,
                                ProfileService profileService,
                                UserLookupCache userLookupCache,
                                CryptoPerformanceClient performanceClient,
                                @Qualifier("cryptoServiceWebClient") WebClient cryptoClient) {
        this.followRepository = followRepository;
        this.profileService = profileService;
        this.userLookupCache = userLookupCache;
        this.performanceClient = performanceClient;
        this.cryptoClient = cryptoClient;
    }

    /**
     * @param viewerUserId  the authenticated user (from JWT) — drives the follow-graph lookup.
     * @param limit         requested page size; clamped to {@code [1, MAX_LIMIT]}; falls back to
     *                      {@link #DEFAULT_LIMIT} on non-positive input.
     */
    public List<FollowingActivityEvent> getFollowingActivity(Long viewerUserId, int limit) {
        int clampedLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 1. Follow graph — empty short-circuit avoids a wasted crypto round-trip.
        Set<Long> followedIds = followRepository.findAllFollowingIds(viewerUserId);
        if (followedIds == null || followedIds.isEmpty()) {
            return List.of();
        }

        // 2. Recent trades — 2× the page limit to account for OPEN+CLOSE expansion.
        int tradesLimit = Math.min(clampedLimit * 2, CRYPTO_TRADES_MAX);
        List<InternalTradeDto> trades = fetchRecentTrades(followedIds, tradesLimit);
        if (trades.isEmpty()) {
            return List.of();
        }

        // 3. Expand to events (one per OPEN, two per CLOSED).
        List<FollowingActivityEvent> events = new ArrayList<>(trades.size() * 2);
        for (InternalTradeDto t : trades) {
            events.add(buildOpenedEvent(t));
            if ("CLOSED".equals(t.status()) && t.closedAt() != null) {
                events.add(buildClosedEvent(t));
            }
        }

        // 4. Sort by occurredAt DESC, page-cap.
        events.sort(Comparator.comparing(FollowingActivityEvent::occurredAt).reversed());
        if (events.size() > clampedLimit) {
            events = events.subList(0, clampedLimit);
        }

        // 5. Hydrate — one batch call per side (profile + perf), keyed on the deduped userId set.
        Set<Long> distinctUserIds = events.stream()
                .map(FollowingActivityEvent::userId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, ProfileSummaryResponse> profilesById = fetchProfileSummaries(distinctUserIds);
        Map<Long, TraderPerf> perfById = performanceClient.getRollingReturnsBatch(distinctUserIds);

        return events.stream()
                .map(e -> hydrate(e, profilesById, perfById))
                .toList();
    }

    // ── Pipeline helpers ────────────────────────────────────────────────────

    private List<InternalTradeDto> fetchRecentTrades(Set<Long> followedIds, int tradesLimit) {
        String userIdsCsv = followedIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            List<InternalTradeDto> result = cryptoClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/internal/trades/recent")
                            .queryParam("userIds", userIdsCsv)
                            .queryParam("limit", tradesLimit)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<InternalTradeDto>>() {})
                    .timeout(TRADES_TIMEOUT)
                    .block();
            return result != null ? result : List.of();
        } catch (Exception e) {
            log.warn("FollowingFeedService: trades fetch failed for {} followed users: {}",
                    followedIds.size(), e.getMessage());
            return List.of();
        }
    }

    private Map<Long, ProfileSummaryResponse> fetchProfileSummaries(Set<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        try {
            List<ProfileSummaryResponse> summaries = profileService.getBatchSummaries(new ArrayList<>(userIds));
            return summaries.stream()
                    .collect(Collectors.toMap(ProfileSummaryResponse::userId, p -> p, (a, b) -> a));
        } catch (Exception e) {
            log.warn("FollowingFeedService: profile-batch lookup failed for {} userIds: {}",
                    userIds.size(), e.getMessage());
            return Map.of();
        }
    }

    private FollowingActivityEvent hydrate(FollowingActivityEvent event,
                                           Map<Long, ProfileSummaryResponse> profilesById,
                                           Map<Long, TraderPerf> perfById) {
        ProfileSummaryResponse profile = profilesById.get(event.userId());
        String username = profile != null ? profile.username() : null;
        String avatarUrl = profile != null ? profile.avatarUrl() : null;

        // Profile fetch is fm-social's own DB but the username may be missing
        // when a user has no profile row yet (newly-followed account, no
        // /auth/me call yet). Fall back to UserLookupCache → finmates-main.
        if (username == null) {
            username = userLookupCache.getByUserId(event.userId())
                    .map(UserLookupCache.UserSummary::username)
                    .orElse(null);
        }

        TraderPerf perf = perfById.getOrDefault(event.userId(), TraderPerf.EMPTY);

        return new FollowingActivityEvent(
                event.eventId(),
                event.userId(),
                username,
                avatarUrl,
                event.eventType(),
                event.symbol(),
                event.side(),
                event.qty(),
                event.entryPrice(),
                event.exitPrice(),
                event.pnlPct(),
                event.occurredAt(),
                perf
        );
    }

    // ── Event-shape factories ───────────────────────────────────────────────

    private static FollowingActivityEvent buildOpenedEvent(InternalTradeDto t) {
        return new FollowingActivityEvent(
                t.tradeId() + "-OPEN",
                t.userId(),
                null,                    // hydrated later
                null,                    // hydrated later
                "POSITION_OPENED",
                t.symbol(),
                normalizeSide(t.side()),
                t.quantity(),
                t.price(),
                null,
                null,
                t.timestamp(),
                TraderPerf.EMPTY         // hydrated later
        );
    }

    private static FollowingActivityEvent buildClosedEvent(InternalTradeDto t) {
        return new FollowingActivityEvent(
                t.tradeId() + "-CLOSE",
                t.userId(),
                null,
                null,
                "POSITION_CLOSED",
                t.symbol(),
                normalizeSide(t.side()),
                t.quantity(),
                t.price(),
                t.exitPrice(),
                computePnlPct(t),
                t.closedAt(),
                TraderPerf.EMPTY
        );
    }

    /**
     * {@code realizedPnl / (entryPrice * quantity) * 100}, 2dp HALF_UP.
     * Returns null when the inputs don't support a meaningful percentage —
     * same null-on-insufficient-data discipline as {@link TraderPerf}.
     */
    private static BigDecimal computePnlPct(InternalTradeDto t) {
        if (t.realizedPnl() == null || t.price() == null || t.quantity() == null) return null;
        BigDecimal denom = t.price().multiply(t.quantity());
        if (denom.signum() == 0) return null;
        return t.realizedPnl()
                .divide(denom, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Maps trade-side names (BUY/SELL) onto position-side names (LONG/SHORT)
     * for the FE — the dashboard widget displays "opened LONG / opened SHORT",
     * not "opened BUY / opened SELL". Falls through unchanged when the input
     * is already a position-side label.
     */
    private static String normalizeSide(String side) {
        if (side == null) return null;
        return switch (side) {
            case "BUY" -> "LONG";
            case "SELL" -> "SHORT";
            default -> side;
        };
    }
}
