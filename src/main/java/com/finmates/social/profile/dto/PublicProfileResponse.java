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
        boolean isBlocked,
        /** When the profile was created. */
        java.time.OffsetDateTime joinedAt,
        /** The profile owner follows the viewer back (false for anonymous viewers and the owner). */
        boolean followsYou,
        /** Viewer and owner follow each other. */
        boolean isMate,
        long matesCount,
        /** How many of the viewer's mates are also the owner's mates (0 for anonymous viewers and the owner). */
        long mutualMatesCount,
        /** Up to 3 of them, for the "mates of you both" line. */
        java.util.List<ProfileSummaryResponse> mutualMates
) {
    public record Stats(long postsCount, long followersCount, long followingCount) {}
}
