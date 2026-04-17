-- Follow graph (unidirectional).
-- Migrated from finmates-main.follows.
-- follower_id follows followed_id.

CREATE TABLE follows (
    id          BIGSERIAL   PRIMARY KEY,
    follower_id BIGINT      NOT NULL,
    followed_id BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT follows_unique   UNIQUE (follower_id, followed_id),
    CONSTRAINT follows_no_self  CHECK  (follower_id != followed_id)
);

CREATE INDEX idx_follows_follower ON follows(follower_id);
CREATE INDEX idx_follows_followed ON follows(followed_id);

-- Block list.
-- blocker_id has blocked blocked_id.
-- Blocks are checked on feed, comment, and reaction reads to suppress content.

CREATE TABLE blocks (
    id          BIGSERIAL   PRIMARY KEY,
    blocker_id  BIGINT      NOT NULL,
    blocked_id  BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT blocks_unique  UNIQUE (blocker_id, blocked_id),
    CONSTRAINT blocks_no_self CHECK  (blocker_id != blocked_id)
);

CREATE INDEX idx_blocks_blocker ON blocks(blocker_id);
CREATE INDEX idx_blocks_blocked ON blocks(blocked_id);
