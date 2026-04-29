# CP5(c) PR-1 — Gate 0 Pre-Merge Diagnostic

**Date:** 2026-04-29
**Author:** Phase 1 diagnostic (Claude)
**Scope:** Read-only. No DB connections. No commits. Determines whether `EXPLAIN ANALYZE` on `findAllMateIds` is safe to run, where to run it, and whether a pre-merge schema migration is needed.

**Branch under review:** `feat/cp5-c-internal-follows-api`
**File under review:** `src/main/java/com/finmates/social/follow/FollowRepository.java:166`

---

## 1. Database configuration audit

### Per-profile decision matrix

| Service | Profile | Host | DB | Schema | Username | Notes |
|---|---|---|---|---|---|---|
| fm-social | (default in `application.yml`) | — | — | — | — | No URL set in base; profile-specific only |
| fm-social | dev (`application-dev.yml`) | `finmates.com:5432` | `social` | (default `public`) | `${DB_USERNAME:user}` | Dev laptops connect via public DNS |
| fm-social | k8s (`application-k8s.yml`) | `postgres.dev.svc.cluster.local:5432` | `social` | (default `public`) | `user` | Cluster-internal DNS |
| fm-social | aws | — | — | — | — | **No `application-aws.yml` exists** — fm-social does not deploy to AWS |
| finmates-main | dev | `finmates.com:5432` | `main` | `public` | `user` | Same host as fm-social dev, different DB |
| finmates-main | k8s | `postgres:5432` | `main` | `public` | `user` | Cluster-internal (different svc name `postgres` vs `postgres.dev.svc.cluster.local` — likely a Kubernetes Service short-name vs FQDN of the same service) |
| finmates-main | aws | `stocks-db.ctquwcg6tlsr.us-east-1.rds.amazonaws.com:5432` | `main` | `public` | `master` | RDS prod |
| finmates-crypto | dev | `finmates.com:5432` | `crypto` | `public` | `user` | Same host, different DB |
| finmates-crypto | k8s | `postgres.dev.svc.cluster.local:5432` | `crypto` | `public` | `user` | Cluster-internal |

### Cross-service sharing (DB level)

| Question | Answer |
|---|---|
| (a) Same PG host across services? | **Yes within an environment.** Dev laptops → `finmates.com:5432`. K8s pods → `postgres[.dev.svc.cluster.local]:5432` (the `postgres` short-name and the FQDN resolve to the same cluster Service). |
| (b) Same DB name? | **No.** Each service owns its own logical DB: `main`, `crypto`, `social`, `crypto_data`. PG-level isolation. |
| (c) Same schema? | All services use the default `public` schema within their own DB. Across DBs, irrelevant — different DB = different schema. |
| (d) Same `follows` table? | **`follows` exists ONLY in the `social` DB** (created by fm-social's V5 migration). `main.follows` is the legacy table on its way to deletion in CP5(c) step 5; it has been confirmed empty. `crypto` has no `follows` table. |

### fm-social specifically — does dev share the prod `follows` table?

**Yes — likely the same PG instance, the same `social` DB, the same `follows` table** for both `dev` and `k8s` profiles.

- `dev` URL `finmates.com:5432/social` — public DNS for the prod stack PG instance.
- `k8s` URL `postgres.dev.svc.cluster.local:5432/social` — cluster-internal DNS, same PG instance.
- The username and password literal (`user` / `Int6859351!`) are identical in both YAMLs, which is consistent with one shared PG instance (or a fully cloned dev instance that happens to share creds — but the simpler explanation is one instance).
- There's no `application-aws.yml` for fm-social, so there's no separate AWS prod DB. Whatever lives behind `finmates.com:5432/social` IS the production data.

> **Caveat — env-var override:** `application-dev.yml` declares `username: ${DB_USERNAME:user}` and `password: ${DB_PASSWORD:Int6859351!}`. If a developer has set `DB_USERNAME` / `DB_PASSWORD` to point at a personal local PG with a separately-created `social` DB, then their dev would NOT touch the cluster instance. This cannot be verified from the codebase alone — it depends on the developer's shell env. Mike should confirm.
>
> The `application-k8s.yml` username/password are hardcoded literals (no env override on those fields), so the k8s pods unambiguously hit the cluster `postgres` Service. Any divergence is on the dev side.

---

## 2. The actual `findAllMateIds` query

`FollowRepository.java:158-166`:

```java
@Query("SELECT f.followedId FROM Follow f " +
        "WHERE f.followerId = :userId " +
        "  AND f.status = com.finmates.social.follow.FollowStatus.ACTIVE " +
        "  AND EXISTS (" +
        "      SELECT 1 FROM Follow rf " +
        "      WHERE rf.followerId = f.followedId " +
        "        AND rf.followedId = :userId " +
        "        AND rf.status = com.finmates.social.follow.FollowStatus.ACTIVE)")
java.util.Set<Long> findAllMateIds(@Param("userId") Long userId);
```

| Property | Value |
|---|---|
| Query type | **JPQL** (`nativeQuery=true` is NOT set; default is JPQL) |
| Parameter bindings | `:userId` (Long) |
| Return type | `java.util.Set<Long>` |
| Status filter | `com.finmates.social.follow.FollowStatus.ACTIVE` enum literal — translated by Hibernate to a string compare against the VARCHAR column |

**Predicted Hibernate-translated SQL** (manual translation; verify against a live `org.hibernate.SQL=DEBUG` run when possible):

```sql
SELECT f.followed_id
FROM follows f
WHERE f.follower_id = ?
  AND f.status = 'ACTIVE'
  AND EXISTS (
      SELECT 1
      FROM follows rf
      WHERE rf.follower_id = f.followed_id
        AND rf.followed_id = ?
        AND rf.status = 'ACTIVE'
  );
```

The two `?` placeholders both bind to `:userId` (same parameter, used twice).

> **Why JPQL here is fine for plan analysis.** The predicates are straightforward column references with literal status values; Hibernate will not introduce any unexpected joins or subqueries that would change the plan. The plan you see from the predicted SQL above is the plan PG will see at runtime.

> **Capturing the actual translated SQL.** Setting `logging.level.org.hibernate.SQL=DEBUG` during a real boot of fm-social would print the SQL on first invocation. The `application.yml` base config currently has `org.hibernate.SQL: INFO` — flip to `DEBUG` for the verification window if needed. This is NOT done in this diagnostic; flagged for completeness.

---

## 3. Index inventory on the `follows` table

### Source of truth: Flyway migrations

#### V5 (`V5__create_follows_and_blocks.sql`) — initial table

```sql
CREATE TABLE follows (
    id          BIGSERIAL   PRIMARY KEY,
    follower_id BIGINT      NOT NULL,
    followed_id BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT follows_unique   UNIQUE (follower_id, followed_id),
    CONSTRAINT follows_no_self  CHECK  (follower_id != followed_id)
);

CREATE INDEX idx_follows_follower ON follows(follower_id);
CREATE INDEX idx_follows_followed ON follows(followed_id);
```

#### V16 (`V16__follow_status_and_privacy.sql`) — status column + composite indexes

```sql
ALTER TABLE follows
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT follows_status_check CHECK (status IN ('ACTIVE', 'PENDING'));

CREATE INDEX idx_follows_followed_status ON follows (followed_id, status);
CREATE INDEX idx_follows_follower_status ON follows (follower_id, status);
```

### Source of truth: `Follow.java` entity annotations

`Follow.java` uses `@Table(name = "follows")` with no `indexes = {…}` clause. All index management is in migrations. The entity defines the columns (`id`, `follower_id`, `followed_id`, `status`, `created_at`) but never specifies indexes via JPA annotations.

### Consolidated index list

| Index name | Columns | Type | Unique | Provenance |
|---|---|---|---|---|
| (PK) `follows_pkey` | `(id)` | BTREE | yes | V5 (`PRIMARY KEY`) |
| `follows_unique` | `(follower_id, followed_id)` | BTREE | yes | V5 (`UNIQUE` constraint) |
| `idx_follows_follower` | `(follower_id)` | BTREE | no | V5 |
| `idx_follows_followed` | `(followed_id)` | BTREE | no | V5 |
| `idx_follows_follower_status` | `(follower_id, status)` | BTREE | no | V16 |
| `idx_follows_followed_status` | `(followed_id, status)` | BTREE | no | V16 |

### Why this matters for `findAllMateIds`

The query has two access patterns:

- **Outer scan**: `WHERE f.follower_id = ? AND f.status = 'ACTIVE'` → covered by `idx_follows_follower_status` exactly.
- **EXISTS subquery**: `WHERE rf.follower_id = f.followed_id AND rf.followed_id = ? AND rf.status = 'ACTIVE'` → can use either `follows_unique (follower_id, followed_id)` (highly selective, then status as residual predicate) or `idx_follows_follower_status` (then `followed_id = ?` as residual). PG's planner picks the more selective one.

**Both paths have indexed coverage out of the box.** No additional indexes appear necessary based on schema inspection alone. The Phase 0 design doc §1b.i hypothesis ("indexes are likely already in place") is **confirmed** by V16. The remaining unknown is whether the planner actually USES them at production row counts — this is what the live `EXPLAIN ANALYZE` would verify.

### Source-of-truth limitation

`spring.jpa.hibernate.ddl-auto: validate` (per `application.yml:8`). Hibernate does NOT auto-create indexes outside Flyway's view in this configuration. Flyway is the sole owner. The index list above should match what PG actually has, modulo any manual `CREATE INDEX` Mike or another operator may have run out-of-band — that history isn't visible from the codebase. **Confirm with `\d follows` in psql** when running the diagnostic.

---

## 4. Row count estimation

| Source | Finding |
|---|---|
| `INSERT INTO follows` SQL grep across `fm-social/` | **No matches.** No seed data, no test fixtures. |
| V16 migration comment (line 15-16) | *"No backfill needed: legacy social-graph data confirmed empty/dev-only and dropped in Phase 7."* |
| Test fixtures | None. fm-social has no `@DataJpaTest` infrastructure (per `ConnectionsPermissionServiceTest` javadoc — JPA tests deferred). |
| Production data | Unknown from codebase alone. Mike has stated "no realistic data" — consistent with the V16 comment. |

**Estimated row count: ~0 to a few hundred at most**, based on the V16 statement and the absence of any production traffic ingesting follows pre-CP5(c). The codebase agrees with Mike's "no realistic data" sanity check.

---

## 5. Safety assessment for running `EXPLAIN ANALYZE`

### Recommendation: **(b) SAFE-BUT-USELESS**

Reasoning:

1. **Read-only operation.** `EXPLAIN ANALYZE SELECT …` on a JPQL-equivalent SQL doesn't write to the table. No schema change, no data change. Even if the query targets the production `social.follows` table, it cannot damage data.
2. **Same logical DB across dev and k8s.** Per §1, the `social` DB is the same instance for both profiles. EXPLAIN ANALYZE run from a developer laptop targets the same `follows` table the production cluster reads from. This is fine for a SELECT — no coordination needed for a read-only query — but it does mean the diagnostic isn't isolatable.
3. **The plan will be misleading at low row counts.** Per §4, `follows` is essentially empty (V16 comment, 2026-04-27). PostgreSQL's planner correctly prefers `Seq Scan` over `Index Scan` when the table has fewer than ~50–100 rows; the per-tuple overhead of an index lookup beats reading the whole table from a single page. **A Seq Scan in this regime says nothing about the production-scale plan.** It would be a false negative — failing Gate 0's pass criteria for a reason unrelated to the indexes' adequacy.
4. **Indexes look correct from schema inspection.** Per §3, `idx_follows_follower_status` and `idx_follows_followed_status` cover the access patterns exactly. With production-realistic data (10k+ rows, mate density ~5%), the plan should naturally choose Index Scan or Index-Only Scan.

### What to do instead

Two viable paths, in increasing order of effort:

#### (i) **Defer Gate 0 to post-merge smoke verification.** (RECOMMENDED)

The schema indexes are demonstrably correct (V16 added the exact composites cp5-c-design.md §1b.i was looking for). Run `EXPLAIN ANALYZE` against the live DB AFTER PR-1 deploys to k8s, once real traffic starts populating the table during step 4 of the deploy plan (cp5-c-design.md §5 step 4 "watch period"). At that point the table has real data, the plan is meaningful, and any Seq Scan finding triggers an immediate rollback (flag flip on main side, no schema change needed).

This matches the "DEFERRED — verify before merge" status the original PR-1 description already declared, and converts it to "DEFERRED — verify during step 4 watch."

#### (ii) **Seed dev `social.follows` with synthetic data, then `EXPLAIN ANALYZE`.**

Only worth it if Mike has lower confidence in the V16 indexes than the schema would suggest, or wants to lock the plan in BEFORE production traffic arrives.

Suggested seed (safe, idempotent, restricted to a synthetic user-ID range so it won't collide with future real users):

```sql
-- Restrict synthetic IDs to a high range to avoid collision with real users.
-- 100k follow rows: 10k synthetic users × ~10 follows each, with ~5% mutual follows.
INSERT INTO follows (follower_id, followed_id, status, created_at)
SELECT
    900000 + (random() * 9999)::bigint AS follower_id,
    900000 + (random() * 9999)::bigint AS followed_id,
    CASE WHEN random() < 0.95 THEN 'ACTIVE' ELSE 'PENDING' END AS status,
    NOW() - (random() * interval '90 days')
FROM generate_series(1, 100000)
ON CONFLICT (follower_id, followed_id) DO NOTHING;  -- follows_unique
-- Self-follows blocked by follows_no_self CHECK; will silently drop ~0.01% of rows.
```

After verification, clean up:

```sql
DELETE FROM follows WHERE follower_id >= 900000 AND follower_id <= 909999;
```

> **Heads-up on cleanup**: this DELETE is the part that matters for safety. If you forget the WHERE clause, you wipe the table. Wrap in a transaction with explicit row-count check before commit.

### The exact `psql` + `EXPLAIN ANALYZE` command

If Mike opts for path (ii), or wants to run the diagnostic once data exists post-deploy:

```bash
psql "postgresql://user@finmates.com:5432/social" \
  -c "EXPLAIN (ANALYZE, BUFFERS, VERBOSE) \
SELECT f.followed_id \
FROM follows f \
WHERE f.follower_id = 12345 \
  AND f.status = 'ACTIVE' \
  AND EXISTS ( \
      SELECT 1 \
      FROM follows rf \
      WHERE rf.follower_id = f.followed_id \
        AND rf.followed_id = 12345 \
        AND rf.status = 'ACTIVE' \
  );"
```

(Replace `12345` with a userId actually present in the table — pick the user with the most follows for the worst-case plan: `SELECT follower_id, COUNT(*) FROM follows GROUP BY 1 ORDER BY 2 DESC LIMIT 5`.)

**Pass criteria** (per cp5-c-design.md Gate 0):
- Index Scan or Index Only Scan on both passes (outer + EXISTS), referencing `idx_follows_follower_status` / `idx_follows_followed_status` / `follows_unique`.
- No `Seq Scan on follows` anywhere.
- Total execution time < 50 ms.

---

## 6. Pre-staged migration if Gate 0 ends up failing

The V16 indexes already cover the access patterns, so a CP5-c-pre migration **probably is not needed**. Pre-staged in case `EXPLAIN ANALYZE` reveals an issue not predicted from schema inspection alone.

### Candidate index additions

```sql
-- V17__follows_covering_indexes.sql
-- Covering composites for the CP5(c) findAllMateIds self-join.
-- Only needed if EXPLAIN ANALYZE on findAllMateIds shows a Seq Scan
-- despite V16's (follower_id, status) and (followed_id, status) composites.
--
-- These add followed_id (resp. follower_id) to the existing composites,
-- enabling Index-Only Scans (no heap fetch needed) for the EXISTS
-- subquery and the outer SELECT projection.
--
-- CONCURRENTLY: required because fm-social runs in a live cluster.
-- Note: CREATE INDEX CONCURRENTLY cannot run inside a transaction,
-- so this migration must run with Flyway's transactional=false setting
-- OR be applied manually via psql before the next service deploy.
-- See https://www.postgresql.org/docs/current/sql-createindex.html.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_follows_follower_status_followed
    ON follows (follower_id, status, followed_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_follows_followed_status_follower
    ON follows (followed_id, status, follower_id);
```

### Sizing estimate (1M rows in `follows`)

BTREE entry size ≈ 8 (key1) + 8 (key2) + ~4 (status varchar truncated) + 8 (TID) + ~12 overhead ≈ **40 bytes per entry**. Two new indexes × 1M rows × ~40 bytes ≈ **80 MB total**. Negligible relative to a typical PG instance.

(Earlier rule-of-thumb of "~80 bytes per BTREE entry × row count" overestimates somewhat for narrow integer keys; 40 bytes is a more accurate per-entry figure for these specific composites. Using ~80 bytes as a safety upper bound: **160 MB total**.)

### Migration filename + slot

fm-social naming is `V{N}__{snake_case_description}.sql`. Latest is `V16`. Next slot is **V17**. Recommended filename: `V17__follows_covering_indexes.sql`.

> **Important**: this migration would NOT run automatically via Flyway in fm-social's current config because `CREATE INDEX CONCURRENTLY` cannot execute inside a transaction. Two options:
>
> 1. **Run the SQL manually via `psql` against the prod DB** (operator action, not Flyway), then immediately add a `repeatable` Flyway migration that does `CREATE INDEX IF NOT EXISTS` (without CONCURRENTLY) so any future fresh DB has the indexes from a clean Flyway run.
> 2. **Configure Flyway with `executeInTransaction=false`** for V17 specifically. fm-social's `application.yml` doesn't currently set per-migration transaction control; this would be a one-line addition.
>
> Option 1 is simpler given how fm-social's Flyway is currently configured. Document the manual step in the CP5-c-pre prompt.

---

## Stop conditions check

- ☑ `findAllMateIds` has an explicit `@Query` annotation (JPQL). Spring Data derived-query was not in play — confirmed.
- ☑ The dev/prod-share question is answerable from properties files: yes, both fm-social profiles target a DB named `social` on instance(s) that look like one shared PG. Caveat about env-var overrides on dev acknowledged.
- ☑ `follows` table exists per V5; primary key is on `(id)` per V5. Diagnostic premise holds.

**No stop conditions triggered.**

---

## Summary

1. **Diagnostic file:** `F:\Projects\fm-social\docs\cp5-c-pr1-gate-0-diagnostic.md` (this file; uncommitted).
2. **Safety class: (b) SAFE-BUT-USELESS at current row counts.** EXPLAIN ANALYZE is read-only and harmless to run against the prod `social.follows` table, but the table is essentially empty (per V16 comment + zero seed data) so the plan will show Seq Scan regardless of index quality. **A Seq Scan finding here is a false negative.**
3. **Recommended path: defer Gate 0 verification to step 4 of cp5-c-design.md §5** (the production watch period), when real traffic has populated `follows`. The PR-1 description already says "DEFERRED — verify before merge"; this diagnostic suggests that's the wrong wording. Better: "DEFERRED — verify during step 4 watch period; rollback path is the feature flag."
4. **Indexes already cover the query.** V16 added `(follower_id, status)` and `(followed_id, status)` composites. The Phase 0 hypothesis that these "should already be in place" is confirmed.
5. **Migration pre-staged (probably not needed):** `V17__follows_covering_indexes.sql` adds `(follower_id, status, followed_id)` and the symmetric variant for Index-Only Scans. Only run if step 4 watch reveals a Seq Scan despite V16's composites. CONCURRENTLY required → cannot be a normal Flyway migration in this service's config; document the manual psql step.

### Exact command Mike should run (when ready)

```bash
# Replace 12345 with a real high-mate-count userId from production data.
# Run from a host with network access to finmates.com:5432.
psql "postgresql://user@finmates.com:5432/social" -c "
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT f.followed_id
FROM follows f
WHERE f.follower_id = 12345
  AND f.status = 'ACTIVE'
  AND EXISTS (
      SELECT 1 FROM follows rf
      WHERE rf.follower_id = f.followed_id
        AND rf.followed_id = 12345
        AND rf.status = 'ACTIVE'
  );"
```

Pass: Index Scan / Index Only Scan, no Seq Scan, < 50 ms.
Fail: Seq Scan present → apply pre-staged V17 indexes manually via psql, re-run.
