-- Phase B Stage 5 — Schema Drops
-- Date: 2026-04-16
-- Backups: F:\backups\main-post-phase-a-2026-04-16.sql
--          F:\backups\crypto-post-phase-a-2026-04-16.sql

-- ============================================================
-- MAIN DB: Drop 33 profile/social/trading columns from users
-- ============================================================

ALTER TABLE users
    -- Profile presentation (8)
    DROP COLUMN IF EXISTS profile_image,
    DROP COLUMN IF EXISTS thumbnail,
    DROP COLUMN IF EXISTS cover_image,
    DROP COLUMN IF EXISTS bio,
    DROP COLUMN IF EXISTS location,
    DROP COLUMN IF EXISTS display_name,
    DROP COLUMN IF EXISTS website,
    DROP COLUMN IF EXISTS name_display_preference,

    -- Social handles (7)
    DROP COLUMN IF EXISTS twitter_handle,
    DROP COLUMN IF EXISTS discord_handle,
    DROP COLUMN IF EXISTS telegram_handle,
    DROP COLUMN IF EXISTS instagram_handle,
    DROP COLUMN IF EXISTS facebook_handle,
    DROP COLUMN IF EXISTS linkedin_handle,
    DROP COLUMN IF EXISTS whatsapp_handle,

    -- Privacy / visibility (16)
    DROP COLUMN IF EXISTS profile_visibility,
    DROP COLUMN IF EXISTS portfolio_visibility,
    DROP COLUMN IF EXISTS notes_permission,
    DROP COLUMN IF EXISTS signal_comments_permission,
    DROP COLUMN IF EXISTS show_location,
    DROP COLUMN IF EXISTS show_real_name,
    DROP COLUMN IF EXISTS show_pnl,
    DROP COLUMN IF EXISTS show_positions,
    DROP COLUMN IF EXISTS show_trade_history,
    DROP COLUMN IF EXISTS show_trading_activity,
    DROP COLUMN IF EXISTS allow_friend_requests,
    DROP COLUMN IF EXISTS allow_messages,
    DROP COLUMN IF EXISTS allow_followers,
    DROP COLUMN IF EXISTS show_online_status,
    DROP COLUMN IF EXISTS show_in_leaderboard,
    DROP COLUMN IF EXISTS allow_copy_trading,

    -- Trading preferences (5)
    DROP COLUMN IF EXISTS default_wallet_type,
    DROP COLUMN IF EXISTS auto_slippage,
    DROP COLUMN IF EXISTS slippage_percent,
    DROP COLUMN IF EXISTS gas_preference,
    DROP COLUMN IF EXISTS show_testnets,

    -- Virtual balance (1)
    DROP COLUMN IF EXISTS virtual_balance;

-- ============================================================
-- MAIN DB: Drop tables (follows, portfolio_notes)
-- ============================================================

DROP TABLE IF EXISTS follows CASCADE;
DROP TABLE IF EXISTS portfolio_notes CASCADE;

-- ============================================================
-- Verify remaining users columns
-- ============================================================
SELECT column_name
FROM information_schema.columns
WHERE table_name = 'users'
ORDER BY ordinal_position;
