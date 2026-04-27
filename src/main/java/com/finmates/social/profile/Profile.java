package com.finmates.social.profile;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "first_name", length = 64)
    private String firstName;

    @Column(name = "last_name", length = 64)
    private String lastName;

    @Convert(converter = NameDisplayPreferenceConverter.class)
    @Column(name = "name_display_preference", length = 20, nullable = false)
    private NameDisplayPreference nameDisplayPreference = NameDisplayPreference.DISPLAY_NAME;

    /** S3 object key for the user's avatar. Mutually exclusive with profileImageUrl (DB constraint). */
    @Column(name = "avatar_key", length = 500)
    private String avatarKey;

    /** S3 object key for the user's cover image. */
    @Column(name = "cover_key", length = 500)
    private String coverKey;

    // V9 additions

    /** External (OAuth provider) avatar URL — set when user has a Google/social avatar. */
    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    // thumbnail_key and thumbnail_url columns remain in DB but are unmapped until
    // image processing (resize/EXIF strip) is added in a later prompt.

    @Column(name = "location", length = 128)
    private String location;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "twitter_handle", length = 64)
    private String twitterHandle;

    @Column(name = "discord_handle", length = 64)
    private String discordHandle;

    @Column(name = "telegram_handle", length = 64)
    private String telegramHandle;

    @Column(name = "instagram_handle", length = 64)
    private String instagramHandle;

    @Column(name = "facebook_handle", length = 64)
    private String facebookHandle;

    @Column(name = "linkedin_handle", length = 64)
    private String linkedinHandle;

    @Column(name = "whatsapp_handle", length = 64)
    private String whatsappHandle;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_visibility", nullable = false, length = 20)
    private ProfileVisibility profileVisibility = ProfileVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "portfolio_visibility", nullable = false, length = 20)
    private PortfolioVisibility portfolioVisibility = PortfolioVisibility.PUBLIC;

    @Column(name = "show_pnl", nullable = false)
    private boolean showPnl = true;

    @Column(name = "show_positions", nullable = false)
    private boolean showPositions = true;

    @Column(name = "show_location", nullable = false)
    private boolean showLocation = true;

    @Column(name = "show_real_name", nullable = false)
    private boolean showRealName = false;

    @Column(name = "show_trading_activity", nullable = false)
    private boolean showTradingActivity = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "notes_permission", nullable = false, length = 20)
    private PermissionLevel notesPermission = PermissionLevel.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_comments_permission", nullable = false, length = 20)
    private PermissionLevel signalCommentsPermission = PermissionLevel.PUBLIC;

    @Column(name = "allow_copy_trading", nullable = false)
    private boolean allowCopyTrading = false;

    // V9 additions
    @Column(name = "show_trade_history", nullable = false)
    private boolean showTradeHistory = true;

    @Column(name = "show_online_status", nullable = false)
    private boolean showOnlineStatus = true;

    @Column(name = "show_in_leaderboard", nullable = false)
    private boolean showInLeaderboard = true;

    @Column(name = "allow_followers", nullable = false)
    private boolean allowFollowers = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "allow_messages", nullable = false, length = 20)
    private AllowMessages allowMessages = AllowMessages.EVERYONE;

    /**
     * V16 — gates whether new follow requests need approval.
     *
     * <p>{@code true}: incoming follow requests are persisted as
     * {@link com.finmates.social.follow.FollowStatus#PENDING} until the followee accepts.
     * {@code false}: follows go straight to {@link com.finmates.social.follow.FollowStatus#ACTIVE}.</p>
     *
     * <p>Distinct from {@link #profileVisibility}: {@code is_private} is about the
     * <em>follow gate</em> only. PROFILE_BASIC viewing (rendering a profile card so a
     * stranger can hit "Request to follow") is always allowed regardless of {@code is_private}.
     * Field-level filtering still flows through {@code profileVisibility}.</p>
     *
     * <p>When this flips {@code true → false}, all currently-PENDING incoming requests
     * are auto-accepted (transitioned to ACTIVE) — see ProfileService (Phase 3).</p>
     */
    @Column(name = "is_private", nullable = false)
    private boolean isPrivate = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
