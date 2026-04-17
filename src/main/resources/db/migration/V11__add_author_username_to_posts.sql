-- V11: Denormalize author_username onto posts for efficient display without cross-service lookups.
-- Value is captured from JWT preferred_username at post-creation time.
ALTER TABLE posts ADD COLUMN IF NOT EXISTS author_username VARCHAR(50);
