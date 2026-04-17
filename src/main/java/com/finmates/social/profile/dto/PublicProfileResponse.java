package com.finmates.social.profile.dto;

import com.finmates.social.client.PortfolioSummaryResponse;

/**
 * Public profile view — returned by GET /api/profiles/{username}/public.
 * No S3 keys exposed — avatarUrl and coverUrl are presigned GET URLs.
 */
public record PublicProfileResponse(
        Long userId,
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        String coverUrl,
        Stats stats,
        PortfolioSummaryResponse portfolio,
        boolean verified,
        boolean isFollowing,
        boolean isBlocked
) {
    public record Stats(long postsCount, long followersCount, long followingCount) {}
}
