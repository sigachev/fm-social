-- "Who can message you": EVERYONE (people who aren't mates land in Requests), MATES or NOONE.
-- Replaces the unused FOLLOWERS option (nothing enforced it before fm-messaging v2).
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_allow_messages_check;
UPDATE profiles SET allow_messages = 'MATES' WHERE allow_messages = 'FOLLOWERS';
ALTER TABLE profiles ADD CONSTRAINT profiles_allow_messages_check CHECK (allow_messages IN ('EVERYONE', 'MATES', 'NOONE'));
