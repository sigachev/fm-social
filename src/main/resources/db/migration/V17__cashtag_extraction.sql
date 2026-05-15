-- Cashtag extraction infrastructure for the Mentions tab (Phase 1).
--
-- Adds an `extracted_cashtags TEXT[]` column to `comments`, GIN-indexes it,
-- and backfills existing rows by running the cashtag regex over `content`.
--
-- The regex MUST stay in sync with:
--   * BE extractor: com.finmates.social.util.CashtagExtractor
--   * FE  extractor: F:\Projects\finmates-front\src\components\mentions\mentionSegments.ts:18
-- Any change to one of these three regex literals requires changing all three
-- atomically AND running the manual FE/BE cross-check in the extractor test file.
--
-- Pattern: (?<![A-Za-z0-9])\$([A-Za-z][A-Za-z0-9]{0,14})
--   - Lookbehind enforces word-start ($ must not follow alphanumeric)
--   - Symbol starts with a letter, then 0-14 alphanumerics (1-15 chars total)
--   - Mirrors FE TOKEN_REGEX with explicit lookbehind instead of the FE's
--     procedural isAlphaNumeric(text[start - 1]) check
--
-- Backfill uses a single statement (table has 6 rows as of 2026-05-14;
-- gate-checked SELECT COUNT(*) FROM comments before applying). If the table
-- ever exceeds ~100k rows, replace this UPDATE with the batched DO-block
-- pattern documented in fm-social/CLAUDE.md.

ALTER TABLE comments
    ADD COLUMN extracted_cashtags TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX comments_extracted_cashtags_gin
    ON comments USING GIN (extracted_cashtags);

-- Backfill: for each comment, extract all cashtag captures, uppercase them,
-- dedupe, sort, and store. COALESCE handles rows that match zero cashtags
-- (the subquery returns NULL when regexp_matches produces no rows).
UPDATE comments
SET extracted_cashtags = COALESCE(
    (SELECT array_agg(DISTINCT UPPER(m[1]) ORDER BY UPPER(m[1]))
     FROM regexp_matches(
         content,
         '(?<![A-Za-z0-9])\$([A-Za-z][A-Za-z0-9]{0,14})',
         'g'
     ) AS m),
    '{}'::TEXT[]
)
WHERE content IS NOT NULL;
