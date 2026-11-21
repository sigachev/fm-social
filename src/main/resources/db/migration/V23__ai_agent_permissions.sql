-- What AI assistants (ChatGPT, Claude, other MCP apps) may do for a user. A missing row means allowed: by default an
-- assistant can do everything the user approved when connecting it; the user switches capabilities off here.
CREATE TABLE IF NOT EXISTS ai_agent_permissions (
    user_id     BIGINT      NOT NULL,
    capability  VARCHAR(40) NOT NULL,
    allowed     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, capability)
);
