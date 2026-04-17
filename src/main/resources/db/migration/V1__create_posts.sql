CREATE TABLE posts (
    id             BIGSERIAL PRIMARY KEY,
    author_id      BIGINT NOT NULL,
    content        TEXT NOT NULL CHECK (char_length(content) <= 1200),
    media_keys     JSONB NOT NULL DEFAULT '[]'::jsonb,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    visibility     VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    comment_count  INT NOT NULL DEFAULT 0,
    reaction_count INT NOT NULL DEFAULT 0,
    edit_count     INT NOT NULL DEFAULT 0,
    last_edited_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT posts_status_check CHECK (status IN ('ACTIVE','HIDDEN','REMOVED','UNDER_REVIEW')),
    CONSTRAINT posts_visibility_check CHECK (visibility IN ('PUBLIC','FOLLOWERS','PRIVATE')),
    CONSTRAINT posts_media_max_check CHECK (jsonb_array_length(media_keys) <= 10)
);

CREATE INDEX idx_posts_author_created ON posts(author_id, created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX idx_posts_moderation    ON posts(status, created_at DESC)    WHERE status != 'ACTIVE';
CREATE INDEX idx_posts_created_at    ON posts(created_at DESC)            WHERE status = 'ACTIVE';
