-- Notification delivery beyond the bell: browser push (Web Push), email, quiet hours and a daily digest.

-- One row per browser that allowed notifications (a person can have several).
CREATE TABLE push_subscriptions (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    endpoint    VARCHAR(1000) NOT NULL UNIQUE,
    p256dh      VARCHAR(200)  NOT NULL,
    auth        VARCHAR(100)  NOT NULL,
    user_agent  VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_ok_at  TIMESTAMPTZ
);
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);

-- Per category, per channel. NULL means the default (push on except "signals from people you follow",
-- email off everywhere).
ALTER TABLE notification_settings
    ADD COLUMN push  BOOLEAN,
    ADD COLUMN email BOOLEAN;

-- Quiet hours (local time, no push and no pop-ups) and the daily digest.
CREATE TABLE notification_prefs (
    user_id         BIGINT PRIMARY KEY,
    quiet_start     SMALLINT CHECK (quiet_start BETWEEN 0 AND 23),
    quiet_end       SMALLINT CHECK (quiet_end BETWEEN 0 AND 23),
    time_zone       VARCHAR(64),
    digest          BOOLEAN     NOT NULL DEFAULT FALSE,
    digest_hour     SMALLINT    NOT NULL DEFAULT 8 CHECK (digest_hour BETWEEN 0 AND 23),
    last_digest_on  DATE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
