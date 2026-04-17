package com.finmates.social.profile.dto;

import com.finmates.social.profile.AllowMessages;
import com.finmates.social.profile.NameDisplayPreference;
import com.finmates.social.profile.PermissionLevel;
import com.finmates.social.profile.PortfolioVisibility;
import com.finmates.social.profile.ProfileVisibility;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProfileUpdateRequest {

    @Size(max = 500)
    private String bio;

    @Size(max = 64)
    private String displayName;

    @Size(max = 64)
    private String firstName;

    @Size(max = 64)
    private String lastName;

    private NameDisplayPreference nameDisplayPreference;

    // S3 keys — deferred to Prompt 4 (S3 integration); accepted but not processed yet
    @Size(max = 255)
    private String profileImageKey;

    @Size(max = 255)
    private String coverImageKey;

    @Size(max = 255)
    private String thumbnailKey;

    // External OAuth avatar URLs (e.g. Google profile photo)
    @Size(max = 500)
    private String profileImageUrl;

    @Size(max = 500)
    private String thumbnailUrl;

    @Size(max = 128)
    private String location;

    @Size(max = 255)
    private String website;

    @Size(max = 64)
    private String timezone;

    @Size(max = 64)
    private String twitterHandle;

    @Size(max = 64)
    private String discordHandle;

    @Size(max = 64)
    private String telegramHandle;

    @Size(max = 64)
    private String instagramHandle;

    @Size(max = 64)
    private String facebookHandle;

    @Size(max = 64)
    private String linkedinHandle;

    @Size(max = 64)
    private String whatsappHandle;

    private ProfileVisibility profileVisibility;
    private PortfolioVisibility portfolioVisibility;

    private Boolean showPnl;
    private Boolean showPositions;
    private Boolean showLocation;
    private Boolean showRealName;
    private Boolean showTradingActivity;
    private Boolean showTradeHistory;
    private Boolean showOnlineStatus;
    private Boolean showInLeaderboard;
    private Boolean allowFollowers;
    private Boolean allowCopyTrading;

    private PermissionLevel notesPermission;
    private PermissionLevel signalCommentsPermission;

    private AllowMessages allowMessages;
}
