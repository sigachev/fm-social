-- V16: Follow status (ACTIVE / PENDING) + per-profile privacy gate.
--
-- Part of the unified Follow / Connections feature. Adds the `status` column to `follows`
-- so the same table can carry both established follows (ACTIVE) and follow requests
-- against private profiles (PENDING), plus an `is_private` flag on `profiles` that
-- gates whether a new follow defaults to PENDING vs ACTIVE.
--
-- Storage representation note (Phase 1 decision, 2026-04-27):
--   `follows.status` is stored as VARCHAR(20) with a CHECK constraint, NOT a PG enum type.
--   This matches the existing fm-social convention — every other status/visibility column
--   on `Profile` (profileVisibility, portfolioVisibility, notesPermission, etc.) is a
--   VARCHAR-backed Java enum via @Enumerated(EnumType.STRING). Sticking with that pattern
--   here keeps the entity mapping uniform across the service.
--
-- No backfill needed: legacy social-graph data confirmed empty/dev-only and dropped in
-- Phase 7. See follow-feature-notes.md for row counts (2026-04-27 manual run) and decisions.
--
-- ------------------------------------------------------------------------------------------
-- Reversibility (manual rollback if needed before Phase 3 lands):
--
--   DROP INDEX IF EXISTS idx_follows_follower_status;
--   DROP INDEX IF EXISTS idx_follows_followed_status;
--   ALTER TABLE follows  DROP CONSTRAINT IF EXISTS follows_status_check;
--   ALTER TABLE follows  DROP COLUMN IF EXISTS status;
--   ALTER TABLE profiles DROP COLUMN IF EXISTS is_private;
--
--   The migration is additive only — no data is dropped or rewritten. Reversal restores
--   the pre-V16 schema exactly. Any rows inserted after V16 will lose their status/is_private
--   values on rollback (acceptable: Phase 1 deploys before any PENDING flow is wired up
--   end-to-end, so no PENDING rows exist in practice until Phase 4+).
-- ------------------------------------------------------------------------------------------

ALTER TABLE follows
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT follows_status_check CHECK (status IN ('ACTIVE', 'PENDING'));

CREATE INDEX idx_follows_followed_status ON follows (followed_id, status);
CREATE INDEX idx_follows_follower_status ON follows (follower_id, status);

ALTER TABLE profiles
    ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;
