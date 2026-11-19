-- Audiences are Everyone / Mates / Only me. "Followers" was no barrier: anyone can follow a public account with one
-- click, so it is replaced by Mates (people the owner follows back), as V19 did for "who can message you".
-- Enum names stay PUBLIC / MATES / PRIVATE; the app labels them Everyone / Mates / Only me.

ALTER TABLE posts DROP CONSTRAINT IF EXISTS posts_visibility_check;
UPDATE posts SET visibility = 'MATES' WHERE visibility = 'FOLLOWERS';
ALTER TABLE posts ADD CONSTRAINT posts_visibility_check CHECK (visibility IN ('PUBLIC', 'MATES', 'PRIVATE'));

ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_profile_vis_check;
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_portfolio_vis_check;
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_notes_perm_check;
ALTER TABLE profiles DROP CONSTRAINT IF EXISTS profiles_signal_comments_perm_check;
UPDATE profiles SET profile_visibility = 'MATES' WHERE profile_visibility = 'FOLLOWERS';
UPDATE profiles SET portfolio_visibility = 'MATES' WHERE portfolio_visibility = 'FOLLOWERS';
UPDATE profiles SET notes_permission = 'MATES' WHERE notes_permission = 'FOLLOWERS';
UPDATE profiles SET signal_comments_permission = 'MATES' WHERE signal_comments_permission = 'FOLLOWERS';
ALTER TABLE profiles ADD CONSTRAINT profiles_profile_vis_check CHECK (profile_visibility IN ('PUBLIC', 'MATES', 'PRIVATE'));
ALTER TABLE profiles ADD CONSTRAINT profiles_portfolio_vis_check CHECK (portfolio_visibility IN ('PUBLIC', 'MATES', 'PRIVATE'));
ALTER TABLE profiles ADD CONSTRAINT profiles_notes_perm_check CHECK (notes_permission IN ('PUBLIC', 'MATES', 'DISABLED'));
ALTER TABLE profiles ADD CONSTRAINT profiles_signal_comments_perm_check CHECK (signal_comments_permission IN ('PUBLIC', 'MATES', 'DISABLED'));
