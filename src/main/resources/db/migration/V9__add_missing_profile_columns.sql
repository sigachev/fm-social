-- V9: Add missing profile columns discovered during finmates-main migration audit
-- Phase A of social profile extraction (NON-DESTRUCTIVE — adds only)

-- Avatar URL columns for external (OAuth-provider) avatars that cannot be stored as S3 keys
ALTER TABLE profiles ADD COLUMN profile_image_url VARCHAR(500);
ALTER TABLE profiles ADD COLUMN thumbnail_url     VARCHAR(500);

-- Mutual-exclusion constraints: a profile image is either an S3 key OR an external URL, never both
ALTER TABLE profiles ADD CONSTRAINT profile_image_exclusive
    CHECK (profile_image_key IS NULL OR profile_image_url IS NULL);

ALTER TABLE profiles ADD CONSTRAINT thumbnail_exclusive
    CHECK (thumbnail_key IS NULL OR thumbnail_url IS NULL);

-- Privacy/permission columns present in finmates-main.users but absent from initial profiles schema
ALTER TABLE profiles ADD COLUMN show_trade_history   BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE profiles ADD COLUMN show_online_status   BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE profiles ADD COLUMN show_in_leaderboard  BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE profiles ADD COLUMN allow_followers      BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE profiles ADD COLUMN allow_messages       VARCHAR(20) NOT NULL DEFAULT 'EVERYONE';

ALTER TABLE profiles ADD CONSTRAINT profiles_allow_messages_check
    CHECK (allow_messages IN ('EVERYONE', 'FOLLOWERS', 'NOONE'));
