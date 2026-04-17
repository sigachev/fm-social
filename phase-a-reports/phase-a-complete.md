# Phase A Complete — 2026-04-16

## Changes Applied

### fm-social (social DB)

**V9 migration applied:** `V9__add_missing_profile_columns.sql`
- Added `profile_image_url VARCHAR(500)` — external OAuth avatar URLs (Google, etc.)
- Added `thumbnail_url VARCHAR(500)` — external thumbnail URLs
- Added CHECK constraint `profile_image_exclusive` — key and url are mutually exclusive
- Added CHECK constraint `thumbnail_exclusive` — key and url are mutually exclusive
- Added `show_trade_history BOOLEAN NOT NULL DEFAULT TRUE`
- Added `show_online_status BOOLEAN NOT NULL DEFAULT TRUE`
- Added `show_in_leaderboard BOOLEAN NOT NULL DEFAULT TRUE`
- Added `allow_followers BOOLEAN NOT NULL DEFAULT TRUE`
- Added `allow_messages VARCHAR(20) NOT NULL DEFAULT 'EVERYONE'` with CHECK IN ('EVERYONE','FOLLOWERS','NOONE')
- Flyway now at version 9 (V1–V9)

**17 profile rows inserted:**
- All 17 users from `finmates-main.users` have a corresponding profile row
- 5 users with meaningful customization exported with full data (users 1, 4, 5, 7, 8, 9, 15, 16, 17 — 9 total with bio/images/location)
- Remaining 8 users received full rows with all defaults applied from the export query
- `portfolio_visibility = 'FRIENDS'` → normalized to `'FOLLOWERS'` (user 9)
- `allow_messages = 'everyone'` → normalized to `'EVERYONE'` (all users)
- User 1 image fields set to NULL (S3 wrong-path bug — FIX deferred to separate prompt after AWS access restored)

### finmates-crypto (crypto DB)

**V8 migration applied:** `V8__create_user_trading_state.sql`
- Created `user_trading_state` table: PK `user_id BIGINT`, `virtual_balance NUMERIC(20,8) NOT NULL DEFAULT 10000`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`
- Flyway now at version 8 (V1, V2, V3, V5, V6, V7, V8 — V4 was pre-existing gap)

**17 rows migrated into user_trading_state:**
- user_id=1: `virtual_balance = 7872.09` (migrated from main — had traded)
- user_id=9: `virtual_balance = 8974.585` (migrated from main — had traded)
- All other 15 users: `virtual_balance = 10000` (default, never traded)

**New Java files created:**
- `model/UserTradingState.java` — JPA entity, `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`, `@PrePersist`/`@PreUpdate` lifecycle hooks
- `repository/UserTradingStateRepository.java` — `JpaRepository<UserTradingState, Long>`, no custom queries needed yet (Phase B adds service layer)

### finmates-main (NO schema/code changes)

- **`com.auth0:java-jwt` removed from `pom.xml`** — was declared but never imported anywhere. Zero risk.
- No other changes. Java code cleanup deferred to Phase B.

### Kubernetes

- `finmates-k8s/secrets/fm-internal-secret.yaml` created with generated 32-byte secret
- `finmates-k8s/secrets/fm-internal-secret.yaml.template` created (placeholder, safe to commit)
- `finmates-k8s/secrets/README.md` created (purpose, env var, rotation instructions)
- `finmates-k8s/.gitignore` created (excludes real secret file from git)
- **kubectl apply pending** — kubectl not available on local dev box during this session. Apply manually:
  ```bash
  kubectl apply -f F:\Projects\finmates-k8s\secrets\fm-internal-secret.yaml
  ```

---

## Decisions Made at STOP 1

| Decision | Choice | Rationale |
|----------|--------|-----------|
| virtual_balance approach | **B** — new `user_trading_state` table | `virtual_balance` is user-scoped; adding to multi-portfolio `portfolios` table was architecturally wrong |
| User 1 avatar | **NULL** — all image fields cleared | AWS access not available; FIX (copy S3 files) deferred to separate prompt. Reference: `user-1-avatar-anomaly.md` |
| JJWT callers | **OK** — none outside expected scope | No unexpected callers found. Additional: removed unused `com.auth0:java-jwt` from pom.xml |

---

## Verification Results (Stage 7)

All 15 data/schema checks passed:

| Check | Result |
|-------|--------|
| V9 columns present in profiles | PASS |
| fm-social at Flyway V9 | PASS |
| user_trading_state table exists | PASS |
| finmates-crypto at Flyway V8 | PASS |
| 17 profile rows in social DB | PASS |
| User 1 all image fields NULL | PASS |
| User 4 profile_image_key (S3) | PASS |
| User 5 profile_image_key (S3) | PASS |
| User 7 profile_image_url (Google) | PASS |
| User 8 profile_image_url (Google) | PASS |
| No portfolio_visibility='FRIENDS' | PASS |
| All allow_messages UPPERCASE | PASS |
| profile_image_exclusive constraint | PASS |
| thumbnail_exclusive constraint | PASS |
| 17 rows in user_trading_state | PASS |
| User 1 virtual_balance=7872.09 | PASS |
| User 9 virtual_balance=8974.585 | PASS |

Skipped (infrastructure not available on dev box):
- K8s secret apply/verify (kubectl not in local PATH) — apply manually
- Backup file existence (files are on remote PostgreSQL server, not local disk)

---

## Files Produced by Phase A

### Migration files (committed to source)
- `finmates-crypto/src/main/resources/db/migration/V8__create_user_trading_state.sql`
- `finmates-crypto/src/main/java/com/finmates/model/UserTradingState.java`
- `finmates-crypto/src/main/java/com/finmates/repository/UserTradingStateRepository.java`
- `fm-social/src/main/resources/db/migration/V9__add_missing_profile_columns.sql`

### K8s manifests
- `finmates-k8s/secrets/fm-internal-secret.yaml` ← **DO NOT COMMIT — contains real secret**
- `finmates-k8s/secrets/fm-internal-secret.yaml.template` ← safe to commit
- `finmates-k8s/secrets/README.md`
- `finmates-k8s/.gitignore`

### Phase A working files (not for commit)
- `fm-social/phase-a-export/profiles-export.csv` — 17-row profile export
- `fm-social/phase-a-export/virtual-balance-export.csv` — 17-row balance export
- `fm-social/phase-a-export/export_profiles.py`
- `fm-social/phase-a-export/export_profiles.sql`
- `fm-social/phase-a-export/import_profiles.py`
- `fm-social/phase-a-export/migrate_virtual_balance.py`
- `fm-social/phase-a-export/apply_migrations.py`
- `fm-social/phase-a-export/verify.py`

### Stage 1 investigation reports (keep for Phase B reference)
- `fm-social/phase-a-reports/virtual-balance-investigation.md`
- `fm-social/phase-a-reports/user-1-avatar-anomaly.md` ← needed for FIX deferred task
- `fm-social/phase-a-reports/jjwt-callers.md` ← needed for Phase B code deletion

---

## Ready for Phase B

**Phase B prompt (2.5b):** Destructive cleanup — code removal, schema column drops, security hardening.

### DO NOT proceed to Phase B until:

1. **Fresh backups taken post-Phase-A** (before Phase B makes destructive changes):
   ```bash
   pg_dump -h finmates.com -U user -d main   > F:\backups\main-post-phase-a.sql
   pg_dump -h finmates.com -U user -d crypto > F:\backups\crypto-post-phase-a.sql
   pg_dump -h finmates.com -U user -d social > F:\backups\social-post-phase-a.sql
   ```

2. **Apply K8s secret** (if not done):
   ```bash
   kubectl apply -f F:\Projects\finmates-k8s\secrets\fm-internal-secret.yaml
   kubectl get secret fm-internal-secret -n dev
   ```

3. **Restart fm-social and finmates-crypto** so their services pick up the Flyway migrations
   that were applied directly to the DB in this session. Flyway will see the history entries
   and recognize V8/V9 as already applied — no re-run will occur.
   ```bash
   cd fm-social && .\mvnw.cmd spring-boot:run
   cd finmates-crypto && mvn spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   Verify Flyway log shows: "Successfully validated N migrations" (not "Applying migrations")

4. **Spot-check fm-social profile reads** via the API:
   ```bash
   curl http://localhost:8091/api/v1/profiles/1  # user 1 — should have bio, no avatar
   curl http://localhost:8091/api/v1/profiles/7  # should have Google avatar URL
   ```

5. **User 1 avatar FIX** — deferred to separate prompt after AWS access restored.
   Reference file: `fm-social/phase-a-reports/user-1-avatar-anomaly.md`
   Files to copy in S3: `users/4/profile/1707876531054-3.jpg` → `users/1/profile/1707876531054-3.jpg`
   and `users/4/profile/1707876531223-3.jpg` → `users/1/profile/1707876531223-3.jpg`
   Then update the profile row: `UPDATE profiles SET profile_image_key='users/1/profile/...', thumbnail_key='users/1/profile/...' WHERE user_id=1`

6. **Explicit user approval** to start Phase B.

---

## Phase A Data is Now Source of Truth

**Do NOT re-run Phase A** — re-running `import_profiles.py` will silently skip on conflict,
but `apply_migrations.py` will detect that V8/V9 are already in flyway_schema_history and skip.
All scripts are idempotent, but there is no need to re-run them.

Profile data in `social.profiles` is now the authoritative store. Phase B will:
- Remove `profile_*` columns from `finmates-main.users` (after verifying fm-social reads are live)
- Remove `virtual_balance` from `finmates-main.users` (after verifying `user_trading_state` reads are live)
- Delete `TokenProvider`, `JwtTokenService`, `JwtAuthenticationFilter` from finmates-main
- Delete asset comment tables from finmates-crypto (migrated to fm-social in earlier phase)
