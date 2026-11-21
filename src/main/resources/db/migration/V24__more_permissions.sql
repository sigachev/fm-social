-- Who can comment on your posts, who can mention you, who can see your followers and following.
-- PUBLIC = everyone, MATES = people you follow back, DISABLED = nobody (only you, for connections).
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS post_comments_permission VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS mentions_permission      VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS connections_visibility   VARCHAR(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE profiles ADD CONSTRAINT profiles_post_comments_perm_check CHECK (post_comments_permission IN ('PUBLIC', 'MATES', 'DISABLED'));
ALTER TABLE profiles ADD CONSTRAINT profiles_mentions_perm_check CHECK (mentions_permission IN ('PUBLIC', 'MATES', 'DISABLED'));
ALTER TABLE profiles ADD CONSTRAINT profiles_connections_vis_check CHECK (connections_visibility IN ('PUBLIC', 'MATES', 'DISABLED'));
