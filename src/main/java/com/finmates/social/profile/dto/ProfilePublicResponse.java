package com.finmates.social.profile.dto;

import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.ProfileVisibility;

import java.time.OffsetDateTime;

public record ProfilePublicResponse(
        Long userId,
        String displayName,
        // name fields null when showRealName=false or profile not accessible at follower level
        String firstName,
        String lastName,
        String profileImageKey,
        String profileImageUrl,
        String thumbnailKey,
        String thumbnailUrl,
        String coverImageKey,
        String bio,
        String location,           // null when showLocation=false
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
        // visibility flags — consumers use these to know what data to request from other services
        boolean showPnl,
        boolean showPositions,
        boolean showTradingActivity,
        boolean showTradeHistory,
        boolean showOnlineStatus,
        boolean showInLeaderboard,
        OffsetDateTime createdAt
) {}
