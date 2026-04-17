-- Backfill author_username for posts created before V11 migration.
-- In dev, map known user IDs to their usernames directly.
-- In prod, run a one-time script that joins against finmates-main.users instead.
UPDATE posts
SET author_username = 'sigachev'
WHERE author_id = 1
  AND author_username IS NULL;
