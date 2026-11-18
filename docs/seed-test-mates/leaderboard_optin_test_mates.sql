-- Test mates (user ids 179-188): turn on "Appear in leaderboard" so they show in the public ranking.
-- Database: crypto. The board is rebuilt nightly (04:23 UTC); see the note below to rebuild now.
--   Get-Content leaderboard_optin_test_mates.sql -Raw | kubectl -n dev exec -i postgres-0 -- sh -c 'psql -U "$POSTGRES_USER" -d crypto -v ON_ERROR_STOP=1'
INSERT INTO leaderboard_settings (user_id, include_in_leaderboard, updated_at)
SELECT id, true, now() FROM generate_series(179, 188) AS id
ON CONFLICT (user_id) DO UPDATE SET include_in_leaderboard = true, updated_at = now();
SELECT user_id, include_in_leaderboard FROM leaderboard_settings WHERE user_id BETWEEN 179 AND 188 ORDER BY user_id;
-- Undo: UPDATE leaderboard_settings SET include_in_leaderboard = false WHERE user_id BETWEEN 179 AND 188;
