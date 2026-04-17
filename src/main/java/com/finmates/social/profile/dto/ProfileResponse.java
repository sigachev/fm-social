package com.finmates.social.profile.dto;

import com.finmates.social.profile.AllowMessages;
import com.finmates.social.profile.NameDisplayPreference;
import com.finmates.social.profile.PermissionLevel;
import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.ProfileVisibility;

import java.time.OffsetDateTime;

/**
 * Full profile response — returned only to the profile owner via GET /api/profiles/me.
 *
 * <p>Raw S3 keys (avatarKey, coverKey) are never exposed. The URL fields contain presigned
 * GET URLs (1h TTL) generated fresh per request, or external OAuth URLs where the user has
 * a Google/social avatar. Thumbnail support is deferred until image processing is added.
 */
public record ProfileResponse(
        Long userId,
        String bio,
        String displayName,
        String firstName,
        String lastName,
        NameDisplayPreference nameDisplayPreference,
        /** Presigned S3 URL (from avatarKey) or external OAuth avatar URL. Null if neither is set. */
        String avatarUrl,
        /** Presigned S3 URL for cover image. Null if not set. */
        String coverUrl,
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
        boolean showLocation,
        boolean showRealName,
        boolean showTradingActivity,
        boolean showTradeHistory,
        boolean showOnlineStatus,
        boolean showInLeaderboard,
        boolean allowFollowers,
        boolean allowCopyTrading,
        PermissionLevel notesPermission,
        PermissionLevel signalCommentsPermission,
        AllowMessages allowMessages,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
