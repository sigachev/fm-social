-- V15: Remove scaffolded-but-never-wired moderation tables.
-- V6 (moderation_actions) and V7 (content_reports) were created speculatively;
-- V14 (reports) is the fully-wired replacement for content_reports.
-- moderation_actions has no JPA mapping, no service, and no controller.

DROP TABLE IF EXISTS moderation_actions;
DROP TABLE IF EXISTS content_reports;
