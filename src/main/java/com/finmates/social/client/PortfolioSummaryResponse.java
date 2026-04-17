package com.finmates.social.client;

import java.math.BigDecimal;

/**
 * Minimal portfolio summary fetched from finmates-crypto internal endpoint.
 * Null if crypto service is unreachable or user has no trading data.
 */
public record PortfolioSummaryResponse(
        Long userId,
        boolean hasTradingData,
        BigDecimal totalPnl,
        BigDecimal totalPnlPct,
        int tradesCount,
        int positionsCount
) {}
