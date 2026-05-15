package com.finmates.social.discussion;

/**
 * Aggregate counts for the five Discussion-panel tabs on the token detail page.
 * Returned by {@code GET /api/discussion/token/{symbol}/counts} at panel mount
 * so the FE can render counts in tab labels — e.g. "Your network (0)
 * Platform (12) Comments (1) Mentions (2) News (32)" — without N round-trips.
 *
 * <p><b>{@code yourNetwork}</b> reflects the viewer's follow list and is
 * always {@code 0} for anonymous viewers (no 401 — the endpoint is mixed-auth,
 * matching the Mentions endpoint pattern).</p>
 *
 * <p><b>{@code news}</b> is nullable. fm-social does not own news data
 * (it lives on fm-crypto-data at {@code /api/news/asset/{symbol}}, which has
 * no count endpoint as of Phase 3.0). Rather than wire a new cross-service
 * dependency for a UX nicety, v1 returns {@code null}; the FE renders
 * the News tab label without a count when this field is {@code null}.
 * Building a per-asset news-count endpoint on fm-crypto-data is tracked
 * as a follow-up in {@code fm-social/CLAUDE.md}.</p>
 */
public record DiscussionCounts(
        Long yourNetwork,
        Long platform,
        Long comments,
        Long mentions,
        Long news
) {}
