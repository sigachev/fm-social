-- User-submitted content reports.
-- Deduplicated per (reporter, target) so a user can only report a given item once.
-- Status lifecycle: PENDING -> REVIEWED | DISMISSED.

CREATE TABLE content_reports (
    id          BIGSERIAL   PRIMARY KEY,
    reporter_id BIGINT      NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   BIGINT      NOT NULL,
    reason      VARCHAR(50) NOT NULL,
    notes       TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_by BIGINT,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT reports_target_type_check CHECK (target_type IN ('POST','COMMENT')),
    CONSTRAINT reports_reason_check CHECK (
        reason IN ('SPAM','ABUSE','NSFW','MISINFO','HARASSMENT','OTHER')
    ),
    CONSTRAINT reports_status_check CHECK (status IN ('PENDING','REVIEWED','DISMISSED'))
);

CREATE UNIQUE INDEX idx_reports_dedup    ON content_reports(reporter_id, target_type, target_id);
CREATE INDEX        idx_reports_pending  ON content_reports(status, created_at)     WHERE status = 'PENDING';
CREATE INDEX        idx_reports_resolved ON content_reports(resolved_by, resolved_at DESC)
    WHERE resolved_at IS NOT NULL;
