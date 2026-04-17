-- Profile presentation table.
-- Extracts all display-facing fields from finmates-main.users (bio, avatar, social links,
-- visibility preferences). user_id is a logical foreign key to main.users.id — no DB-level
-- FK constraint because the social DB has no direct link to the main DB.
-- Image columns store S3 object keys, not CDN URLs. URLs are generated at read time.

CREATE TABLE profiles (
    user_id                    BIGINT       PRIMARY KEY,
    bio                        TEXT         CHECK (char_length(bio) <= 500),
    display_name               VARCHAR(64),
    first_name                 VARCHAR(64),
    last_name                  VARCHAR(64),
    name_display_preference    VARCHAR(20)  NOT NULL DEFAULT 'display_name',

    profile_image_key          VARCHAR(255),
    cover_image_key            VARCHAR(255),
    thumbnail_key              VARCHAR(255),

    location                   VARCHAR(128),
    website                    VARCHAR(255),
    timezone                   VARCHAR(64),

    twitter_handle             VARCHAR(64),
    discord_handle             VARCHAR(64),
    telegram_handle            VARCHAR(64),
    instagram_handle           VARCHAR(64),
    facebook_handle            VARCHAR(64),
    linkedin_handle            VARCHAR(64),
    whatsapp_handle            VARCHAR(64),

    profile_visibility         VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    portfolio_visibility       VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    show_pnl                   BOOLEAN      NOT NULL DEFAULT TRUE,
    show_positions             BOOLEAN      NOT NULL DEFAULT TRUE,
    show_location              BOOLEAN      NOT NULL DEFAULT TRUE,
    show_real_name             BOOLEAN      NOT NULL DEFAULT FALSE,
    show_trading_activity      BOOLEAN      NOT NULL DEFAULT TRUE,
    notes_permission           VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    signal_comments_permission VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    allow_copy_trading         BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT profiles_name_pref_check CHECK (
        name_display_preference IN ('full_name','display_name')
    ),
    CONSTRAINT profiles_profile_vis_check CHECK (
        profile_visibility IN ('PUBLIC','FOLLOWERS','PRIVATE')
    ),
    CONSTRAINT profiles_portfolio_vis_check CHECK (
        portfolio_visibility IN ('PUBLIC','FOLLOWERS','PRIVATE')
    ),
    CONSTRAINT profiles_notes_perm_check CHECK (
        notes_permission IN ('PUBLIC','FOLLOWERS','DISABLED')
    ),
    CONSTRAINT profiles_signal_comments_perm_check CHECK (
        signal_comments_permission IN ('PUBLIC','FOLLOWERS','DISABLED')
    )
);

CREATE INDEX idx_profiles_updated_at ON profiles(updated_at DESC);
