-- Polymorphic comments table.
-- target_type IN ('POST','PORTFOLIO','ASSET').
-- POST and PORTFOLIO targets reference a BIGINT id (post.id or profile owner user_id).
-- ASSET targets reference a symbol string (e.g. 'BTC').
-- Exactly one of (target_id, target_symbol) is non-null, enforced by comments_target_exclusive.

CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    author_id       BIGINT       NOT NULL,
    author_username VARCHAR(64)  NOT NULL,   -- denormalized at write time (no join needed for rendering)
    target_type     VARCHAR(20)  NOT NULL,
    target_id       BIGINT,                  -- used when target_type IN ('POST','PORTFOLIO')
    target_symbol   VARCHAR(20),             -- used when target_type = 'ASSET'
    parent_id       BIGINT REFERENCES comments(id) ON DELETE CASCADE,
    content         TEXT         NOT NULL CHECK (char_length(content) <= 500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    reaction_count  INT          NOT NULL DEFAULT 0,
    edit_count      INT          NOT NULL DEFAULT 0,
    last_edited_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT comments_status_check CHECK (status IN ('ACTIVE','HIDDEN','REMOVED','UNDER_REVIEW')),
    CONSTRAINT comments_target_type_check CHECK (target_type IN ('POST','PORTFOLIO','ASSET')),
    CONSTRAINT comments_target_exclusive CHECK (
        (target_type IN ('POST','PORTFOLIO') AND target_id IS NOT NULL AND target_symbol IS NULL)
        OR
        (target_type = 'ASSET' AND target_symbol IS NOT NULL AND target_id IS NULL)
    )
);

CREATE INDEX idx_comments_target_post  ON comments(target_type, target_id, created_at DESC)
    WHERE target_type IN ('POST','PORTFOLIO') AND status = 'ACTIVE';
CREATE INDEX idx_comments_target_asset ON comments(target_type, target_symbol, created_at DESC)
    WHERE target_type = 'ASSET' AND status = 'ACTIVE';
CREATE INDEX idx_comments_author       ON comments(author_id, created_at DESC);
CREATE INDEX idx_comments_parent       ON comments(parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_comments_moderation   ON comments(status, created_at DESC) WHERE status != 'ACTIVE';
