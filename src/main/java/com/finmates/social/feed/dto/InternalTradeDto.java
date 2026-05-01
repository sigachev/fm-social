package com.finmates.social.feed.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire-shape mirror of finmates-crypto's
 * {@code com.finmates.controller.InternalCryptoSocialController.InternalTradeDto}.
 *
 * <p>Returned by {@code GET /api/internal/trades/recent?userIds=...&limit=N}.
 * Defensive copy here so fm-social doesn't depend on a finmates-crypto type
 * (the two services share no jar). Field names and order MUST stay aligned —
 * Jackson deserializes by name, so order doesn't matter at runtime, but
 * matching makes drift obvious in code review.
 *
 * <p>For OPEN trades: {@code status="OPEN"} and the four trailing fields are
 * all null. For CLOSED trades: {@code status="CLOSED"}, all four populated.
 */
public record InternalTradeDto(
        Long tradeId,
        Long userId,
        Long portfolioId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        Instant timestamp,
        String status,
        BigDecimal exitPrice,
        BigDecimal realizedPnl,
        Instant closedAt
) {}
