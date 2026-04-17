-- Phase B Stage 5 — Crypto DB Drops
-- Date: 2026-04-16

-- ============================================================
-- CRYPTO DB: Drop asset_comment and asset_comment_reaction tables
-- ============================================================

DROP TABLE IF EXISTS asset_comment_reaction CASCADE;
DROP TABLE IF EXISTS asset_comment CASCADE;

-- ============================================================
-- Verify drops
-- ============================================================
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('asset_comment', 'asset_comment_reaction')
ORDER BY table_name;
