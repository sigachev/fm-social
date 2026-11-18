-- Test mates (user ids 179-188, seeded 2026-11): turn their watched wallets into
-- owned, verified wallets so profile money, leaderboard and friends' widgets show data.
-- Database: crypto (finmates-crypto, table user_wallets). Test data only.
--
-- Run:
--   kubectl -n dev exec -i postgres-0 -- psql -U postgres -d crypto -v ON_ERROR_STOP=1 < verify_test_wallets.sql
--
-- Undo: see the bottom of this file.

BEGIN;

-- 1. What will change (check it lists only the 10 test users).
SELECT user_id, wallet_address, label, relationship, is_watch_only, verified_at, is_primary
FROM user_wallets
WHERE user_id BETWEEN 179 AND 188
ORDER BY user_id, id;

-- 2. Mark every wallet of the test users as owned and verified.
--    verification_message marks the rows so they can be told apart from real signatures.
UPDATE user_wallets
SET relationship           = 'owner',
    is_watch_only          = false,
    is_active              = true,
    verified_at            = COALESCE(verified_at, now()),
    verification_message   = 'seed-test-mates 2026-11: marked verified for testing (no signature)',
    verification_signature = NULL,
    verification_nonce     = NULL
WHERE user_id BETWEEN 179 AND 188;

-- 3. One primary wallet per test user (their oldest), if they have none yet.
UPDATE user_wallets w
SET is_primary = true
FROM (
    SELECT DISTINCT ON (user_id) id
    FROM user_wallets
    WHERE user_id BETWEEN 179 AND 188
      AND user_id NOT IN (SELECT user_id FROM user_wallets WHERE is_primary AND user_id BETWEEN 179 AND 188)
    ORDER BY user_id, created_at NULLS LAST, id
) first
WHERE w.id = first.id;

-- 4. Result.
SELECT user_id, count(*) AS wallets, count(*) FILTER (WHERE is_primary) AS primary_wallets
FROM user_wallets
WHERE user_id BETWEEN 179 AND 188
GROUP BY user_id
ORDER BY user_id;

COMMIT;

-- Undo (back to watch-only):
-- UPDATE user_wallets
-- SET relationship = 'watcher', is_watch_only = true, is_primary = false,
--     verified_at = NULL, verification_message = NULL
-- WHERE user_id BETWEEN 179 AND 188
--   AND verification_message LIKE 'seed-test-mates 2026-11:%';
