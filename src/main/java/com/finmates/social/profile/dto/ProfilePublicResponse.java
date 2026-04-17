package com.finmates.social.profile.dto;

import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.ProfileVisibility;

import java.time.OffsetDateTime;

/**
 * Visibility-filtered profile response — returned to other users and unauthenticated requests.
 *
 * <p>Raw S3 keys are never exposed. URL fields contain presigned GET URLs or external OAuth URLs.
 * Null values indicate fields the viewer does not have access to (private profile, follower-only, etc.).
 */
public record ProfilePublicResponse(
        Long userId,
        String displayName,
        String firstName,
        String lastName,
        String avatarUrl,
        String thumbnailUrl,
        String coverUrl,
        String bio,
        String location,
        String website,
        String timezone,
        String twitterHandle,
        String discordHandle,
        String telegramHandle,
        String instagramHandle,
        String facebookHandle,
        String linkedinHandle,
        String whatsappHandle,
        ProfileVisibility profileVisibility,
        PortfolioVisibility portfolioVisibility,
        boolean showPnl,
        boolean showPositions,
        boolean showTradingActivity,
        boolean showTradeHistory,
        boolean showOnlineStatus,
        boolean showInLeaderboard,
        OffsetDateTime createdAt
) {}
