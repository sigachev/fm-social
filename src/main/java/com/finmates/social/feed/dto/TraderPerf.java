package com.finmates.social.feed.dto;

import java.math.BigDecimal;

/**
 * Mirror of {@code com.finmates.controller.InternalCryptoSocialController.TraderPerf}
 * in finmates-crypto. The two definitions <b>must stay identical</b> field-for-field
 * — drift is a wire-contract bug.
 *
 * <p>Source endpoint: {@code GET /api/internal/users/{userId}/rolling-returns}
 * (finmates-crypto, see {@code finmates-crypto/CLAUDE.md → Internal API}).
 *
 * <p><b>Null-on-insufficient-data contract.</b> A field is null when the
 * subject user has no equity snapshot at-or-before the window cutoff. <b>Do
 * NOT collapse null to {@code BigDecimal.ZERO}</b> here or anywhere downstream
 * — same rationale as {@code cashBalance} in finmates-crypto's
 * {@code PortfolioService.buildFullDto} /
 * {@code PortfolioSocialController.getSummaryWithSocial} (the comment block at
 * those lines is the canonical reference). The frontend renders {@code "—"}
 * for null and {@code "0.00%"} for zero, and conflating "no data" with "flat"
 * is a meaningfully different lie that compounds across UI surfaces.
 *
 * <p><b>Launch caveat (2026-04-30).</b> {@code user_wallet_snapshots} has only
 * 7 days of history at deploy time, so {@code return1mPct} will be null for
 * <b>every</b> user until 2026-05-23. The em-dash render is correct.
 */
public record TraderPerf(
        BigDecimal return1dPct,
        BigDecimal return1wPct,
        BigDecimal return1mPct
) {
    /** Convenience instance for cache misses on cross-service outage. */
    public static final TraderPerf EMPTY = new TraderPerf(null, null, null);
}
