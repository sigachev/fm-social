-- V10: Rename profile media key columns to align with S3 upload folder structure.
-- profile_image_key → avatar_key  (same data; renamed for clarity with new S3 paths)
-- cover_image_key   → cover_key   (same data; renamed for clarity)
--
-- posts.media_keys already exists (added in V1). No change to posts table.
-- thumbnail_key stays (not renamed — not yet part of structured upload flow).

-- Drop the CHECK constraint that references profile_image_key (V9 added it)
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profile_image_exclusive;

-- Rename columns
ALTER TABLE profiles RENAME COLUMN profile_image_key TO avatar_key;
ALTER TABLE profiles RENAME COLUMN cover_image_key   TO cover_key;

-- Widen to 500 chars (full S3 key paths can exceed 255)
ALTER TABLE profiles ALTER COLUMN avatar_key TYPE VARCHAR(500);
ALTER TABLE profiles ALTER COLUMN cover_key  TYPE VARCHAR(500);

-- Recreate mutual-exclusion CHECK with new column name
-- avatar_key (S3) and profile_image_url (OAuth) must not both be set
ALTER TABLE profiles ADD CONSTRAINT avatar_key_exclusive
    CHECK (avatar_key IS NULL OR profile_image_url IS NULL);
