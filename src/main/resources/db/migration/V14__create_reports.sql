-- V14: User-submitted content reports.
-- Distinct from V7's content_reports table (which is an older, simpler design).
-- This table is the canonical moderation queue consumed by the admin panel.

CREATE TABLE reports (
    id             BIGSERIAL PRIMARY KEY,
    reporter_id    BIGINT       NOT NULL,
    target_type    VARCHAR(20)  NOT NULL CHECK (target_type IN ('POST', 'COMMENT', 'USER')),
    target_id      BIGINT       NOT NULL,
    reason         VARCHAR(50)  NOT NULL,
    details        VARCHAR(1000),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'REVIEWED', 'DISMISSED')),
    resolution_action VARCHAR(30),
    resolved_by    BIGINT,
    resolved_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- One report per (reporter, target_type, target_id) — prevents duplicate submissions
CREATE UNIQUE INDEX uq_reports_reporter_target
    ON reports (reporter_id, target_type, target_id);

-- Index for admin queue: newest pending reports first
CREATE INDEX idx_reports_status_created
    ON reports (status, created_at DESC);

-- Index for "my reports" endpoint
CREATE INDEX idx_reports_reporter
    ON reports (reporter_id, created_at DESC);
