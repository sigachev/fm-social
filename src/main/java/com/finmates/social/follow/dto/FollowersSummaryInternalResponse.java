package com.finmates.social.follow.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response shape for {@code GET /api/internal/follows/followers-summary}.
 *
 * <p>{@code recent} carries the most recent N followers (capped server-side; see
 * {@code FollowsInternalController}), each with their {@code createdAt} so callers can
 * apply their own time-window filter (e.g. "last 7 days" / "last 30 days") without
 * fragmenting the cache key.</p>
 */
public record FollowersSummaryInternalResponse(
        long totalCount,
        List<RecentFollowerEntry> recent
) {
    public record RecentFollowerEntry(
            Long userId,
            OffsetDateTime createdAt
    ) {}
}
