-- In-app notifications (header bell). One row per recipient; fan-out happens at write time.
-- Written by fm-social itself (new follower, follow request) and by other services through
-- POST /api/internal/notifications (finmates-crypto: signals posted, hit target, stopped, backed).
CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(40)  NOT NULL,
    actor_id    BIGINT,
    title       VARCHAR(200) NOT NULL,
    body        VARCHAR(500),
    link        VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread  ON notifications (user_id) WHERE read_at IS NULL;
