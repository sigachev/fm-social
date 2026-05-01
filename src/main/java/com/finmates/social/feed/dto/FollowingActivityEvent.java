package com.finmates.social.feed.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single event row on the {@code GET /api/feed/following} endpoint.
 *
 * <p>One closed trade in finmates-crypto's {@code portfolio_trades} expands
 * into <b>two</b> events in this feed: a {@code POSITION_OPENED} at
 * {@code openedAt} and a {@code POSITION_CLOSED} at {@code closedAt}. Open
 * trades emit only the {@code POSITION_OPENED} variant. The {@code eventId}
 * disambiguates the two halves: {@code "{tradeId}-OPEN"} or
 * {@code "{tradeId}-CLOSE"} — stable client-side keys for React reconciliation.
 *
 * <p>Field nullability:
 * <ul>
 *   <li>{@code exitPrice} — null for OPENED, populated for CLOSED.</li>
 *   <li>{@code pnlPct} — null for OPENED, populated for CLOSED.
 *       Computed in fm-social as {@code realizedPnl / (entryPrice * qty) * 100}
 *       from the extended {@code InternalTradeDto} fields (the controller in
 *       finmates-crypto does not pre-compute, to keep the wire shape thin).</li>
 *   <li>{@code username} — null only on a finmates-main outage at hydration
 *       time. UI falls back to {@code "user_{userId}"}.</li>
 *   <li>{@code avatarUrl} — null when the user has no avatar set. UI falls
 *       back to a hash-derived initial badge.</li>
 *   <li>{@code traderPerf} — never null (we substitute {@link TraderPerf#EMPTY}
 *       on cross-service outage), but its inner fields can be null. See
 *       {@link TraderPerf} javadoc for the null contract.</li>
 * </ul>
 */
public record FollowingActivityEvent(
        String eventId,
        Long userId,
        String username,
        String avatarUrl,
        String eventType,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal pnlPct,
        Instant occurredAt,
        TraderPerf traderPerf
) {}
