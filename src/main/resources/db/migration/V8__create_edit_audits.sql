-- Edit audit tables.
-- Each row captures the content of a post or comment BEFORE an edit,
-- providing a full edit history. Cascades on parent delete.

CREATE TABLE post_edits (
    id               BIGSERIAL   PRIMARY KEY,
    post_id          BIGINT      NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    previous_content TEXT        NOT NULL,
    edited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_post_edits_post ON post_edits(post_id, edited_at DESC);

CREATE TABLE comment_edits (
    id               BIGSERIAL   PRIMARY KEY,
    comment_id       BIGINT      NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    previous_content TEXT        NOT NULL,
    edited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comment_edits_comment ON comment_edits(comment_id, edited_at DESC);
