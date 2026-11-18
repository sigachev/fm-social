-- Per-user on/off for bell notifications, by category. A missing row means "on".
-- Categories: FOLLOWS, PEOPLES_SIGNALS, MY_SIGNALS, MESSAGES.
CREATE TABLE notification_settings (
    user_id  BIGINT      NOT NULL,
    category VARCHAR(30) NOT NULL,
    enabled  BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, category)
);
