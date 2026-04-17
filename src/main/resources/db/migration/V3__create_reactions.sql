-- Unified crypto-native reactions for posts and comments.
-- Replaces the LIKE/DISLIKE enum in finmates-crypto.asset_comment_reaction
-- (migration prompt 2.5 will map old values: LIKE -> BULLISH, DISLIKE -> BEARISH).
-- One row per (user, target, reaction_type): a user can place multiple distinct reaction
-- types on the same target (e.g. both FIRE and BULLISH on a post).

CREATE TABLE reactions (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    target_type   VARCHAR(20) NOT NULL,
    target_id     BIGINT      NOT NULL,
    reaction_type VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT reactions_target_type_check CHECK (target_type IN ('POST','COMMENT')),
    CONSTRAINT reactions_reaction_type_check CHECK (
        reaction_type IN ('BULLISH','BEARISH','FIRE','DIAMOND_HANDS','REKT')
    ),
    CONSTRAINT reactions_unique UNIQUE (user_id, target_type, target_id, reaction_type)
);

CREATE INDEX idx_reactions_target ON reactions(target_type, target_id);
CREATE INDEX idx_reactions_user   ON reactions(user_id, created_at DESC);
