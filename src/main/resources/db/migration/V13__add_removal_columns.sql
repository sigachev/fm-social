-- V13: Add soft-removal audit columns to posts and comments.
-- These are set by admin moderation actions (internal remove endpoint).
-- status = 'REMOVED' is already possible in the enum; these columns capture WHO removed it and WHY.

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS removed_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS removed_by  BIGINT,
    ADD COLUMN IF NOT EXISTS removal_reason VARCHAR(500);

ALTER TABLE comments
    ADD COLUMN IF NOT EXISTS removed_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS removed_by  BIGINT,
    ADD COLUMN IF NOT EXISTS removal_reason VARCHAR(500);
