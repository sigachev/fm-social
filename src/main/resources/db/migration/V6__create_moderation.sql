-- Moderation action log.
-- Records every moderation event applied to a post or comment by a moderator (ROLE_ADMIN).
-- Immutable audit trail — rows are never updated or deleted.

CREATE TABLE moderation_actions (
    id           BIGSERIAL   PRIMARY KEY,
    moderator_id BIGINT      NOT NULL,
    target_type  VARCHAR(20) NOT NULL,
    target_id    BIGINT      NOT NULL,
    action       VARCHAR(20) NOT NULL,
    reason       VARCHAR(50),
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT moderation_target_type_check CHECK (target_type IN ('POST','COMMENT')),
    CONSTRAINT moderation_action_check CHECK (
        action IN ('HIDE','REMOVE','RESTORE','FLAG_REVIEWED','DISMISS')
    ),
    CONSTRAINT moderation_reason_check CHECK (
        reason IS NULL
        OR reason IN ('SPAM','ABUSE','NSFW','MISINFO','HARASSMENT','OTHER')
    )
);

CREATE INDEX idx_moderation_target    ON moderation_actions(target_type, target_id, created_at DESC);
CREATE INDEX idx_moderation_moderator ON moderation_actions(moderator_id, created_at DESC);
