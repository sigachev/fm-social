# Phase B — Completion Report
**Date:** 2026-04-16  
**Status:** COMPLETE — both services build clean, schema drops applied, documentation updated

---

## Summary

Phase B removed all dead code accumulated before the fm-social migration, tightened security on service-to-service communication, and cleaned the main DB schema down to its authoritative 28-column shape.

---

## Scope Completed

### Stage 1 — Investigation Reports (fm-social/phase-b-reports/)
- `oauth2-client-usage.md` — confirmed OAuth2 client was only used by dead Spring OAuth2 login chain
- `deletion-callers.md` — mapped all callers of classes scheduled for deletion
- `user-entity-callers.md` — mapped all callers of User fields scheduled for removal
- `endpoints-to-remove.md` — catalogued all endpoints being deleted with replacement pointers
- `stage5-drops.sql` — main DB column/table drops
- `stage5-drops-crypto.sql` — crypto DB table drops

### Stage 2 — finmates-crypto Code Changes

**Deleted (11 files):**
- `controller/AssetCommentController.java`
- `service/AssetCommentService.java`
- `model/AssetComment.java`, `AssetCommentReaction.java`, `AssetCommentReactionId.java`
- `repository/AssetCommentRepository.java`, `AssetCommentReactionRepository.java`
- `dto/comment/AssetCommentDto.java`, `CreateCommentRequest.java`
- `db/migration/V6__asset_comment_schema.sql`
- `db/migration/V7__asset_comment_reaction.sql`

**Created:**
- `config/security/InternalSecretFilter.java` — `OncePerRequestFilter` validating `X-Internal-Secret` on `/api/internal/**` using `MessageDigest.isEqual()` (timing-safe)

**Modified:**
- `config/auth/JwtAuthConverter.java` — added `realm_access.roles` fallback + `ROLE_USER` default (fixes users without `resource_access` in token)
- `config/WebSecurityConfig.java` — replaced blanket `/api/**` permitAll with specific public-path whitelist; wired `InternalSecretFilter` before `UsernamePasswordAuthenticationFilter`
- `application-dev.properties` + `application-k8s.properties` — added `spring.flyway.ignore-migration-patterns=*:missing` (V6/V7 history rows retained) + `finmates.internal.shared-secret=${INTERNAL_SHARED_SECRET}`

### Stage 3 — finmates-main Code Changes

**Deleted (~26 files):**

| Category | Files |
|----------|-------|
| JWT pipeline (JJWT) | `TokenProvider.java`, `JwtTokenService.java`, `JwtAuthenticationFilter.java` |
| OAuth2 login chain | `OAuth2AuthenticationSuccessHandler.java`, `OAuth2AuthenticationFailureHandler.java`, `CustomOAuth2UserService.java`, `CustomAuthorizationRequestResolver.java`, `HttpCookieOAuth2AuthorizationRequestRepository.java`, `FacebookOAuth2UserInfo.java`, `GoogleOAuth2UserInfo.java`, `OAuth2UserInfo.java`, `OAuth2UserInfoFactory.java`, `OAuth2Config.java` |
| Follow graph | `Follow.java`, `FollowId.java`, `FollowRepository.java`, `SocialService.java`, `SocialController.java` |
| PortfolioNote | `PortfolioNote.java`, `PortfolioNoteRepository.java`, `PortfolioNoteService.java`, `PortfolioNoteController.java`, `PortfolioNoteDTO.java` |
| Deprecated portfolio | `TradeService.java`, `PortfolioService.java`, `SnapshotJob.java`, `PortfolioController.java` |
| Unused controllers | `TraderController.java`, `LeaderboardController.java` |
| Orphaned service | `ImagesService.java` |

**Created:**
- `config/security/InternalSecretFilter.java` — identical timing-safe filter implementation

**Modified:**
- `model/User.java` — removed all 36 drop-list fields; kept identity, auth/security metadata (`two_factor_enabled`, `login_alerts_enabled`, `password_last_changed`), notif_* fields, timezone, verified, soft-delete, timestamps
- `dto/UserDTO.java` — stripped to identity + notif_* fields only (no profile/social/portfolio fields); `@Data @Builder`
- `controller/AuthController.java` — removed `TokenProvider`/`JwtTokenService` injections; injected Spring Security `JwtDecoder`; fixed JWT claim key from `"subject"` → `"sub"`; added `JwtException` catch block
- `controller/SettingsController.java` — deleted profile/avatar/privacy/trading endpoints; kept/created `GET/PUT /settings/notifications` (6 notif_* fields only)
- `controller/UserController.java` — deleted `GET /user/profile/{username}`
- `controller/oauth2/OAuth2Controller.java` — removed unused `OAuth2AuthorizedClientService` field
- `config/WebSecurityConfig.java` — restructured from 3 filter chains to 2; deleted OAuth2 chain; wired `InternalSecretFilter`
- `pom.xml` — removed `spring-boot-starter-oauth2-client`, `jjwt-api`, `jjwt-impl`, `jjwt-jackson`
- `application-dev.properties` + `application-k8s.properties` — removed `app.auth.token-*` properties; added `finmates.internal.shared-secret=${INTERNAL_SHARED_SECRET}`

### Stage 4 — Build Checkpoint
Both services: **BUILD SUCCESS** post-code-changes (before schema drops).

### Stage 5 — Schema Drops

**main DB:**
- Dropped 33 planned profile/social/trading/virtual_balance columns from `users`
- Dropped 6 additional orphaned Hibernate columns (`is_portfolio_public`, `active_chains`, `instruments`, `investor_type`, `risk_tolerance`, `trading_experience`)
- **Total: 39 columns dropped from `users`**
- Dropped `follows` table (CASCADE)
- Dropped `portfolio_notes` table (CASCADE)

**crypto DB:**
- Dropped `asset_comment_reaction` table (CASCADE)
- Dropped `asset_comment` table (CASCADE)

### Stage 6 — Post-Drop Verification

- `users` table: confirmed 28 columns; no profile/social/trading/balance columns remain
- `follows` table: confirmed absent
- `asset_comment` and `asset_comment_reaction` tables: confirmed absent
- Flyway schema history in crypto DB: V6/V7 history rows retained; `ignore-migration-patterns=*:missing` prevents startup failure

### Stage 7 — Documentation Updates

- **`finmates-main/CLAUDE.md`** — major rewrite: updated project overview, auth flow, relationship model, controller mappings, API endpoints, key dependencies, Known Gotchas; added "Deleted in Phase B" callout; added "Deprecated Endpoints (Phase B — REMOVED)" section
- **`finmates-crypto/CLAUDE.md`** — added `InternalUserController` to controller list; added "Deleted in Phase B" callout for AssetComment stack; updated auth flow (InternalSecretFilter, JwtAuthConverter); updated Known Issues
- **Root `CLAUDE.md`** — bumped Last Updated to 2026-04-16; updated `users` table row (28-column note, no virtual_balance/is_portfolio_public); removed `follows` table row; added Phase B schema evolution note; added `user_trading_state` (V8) to crypto DB; added V6/V7 deletion notes; resolved Known Open Issue for fm-social migration

### Stage 8 — Final Build Verification

| Service | Result |
|---------|--------|
| `finmates-main` | ✅ BUILD SUCCESS |
| `finmates-crypto` | ✅ BUILD SUCCESS |

---

## Key Decisions Made

| Decision | Rationale |
|----------|-----------|
| Keep `two_factor_enabled`, `login_alerts_enabled`, `password_last_changed` | Auth/security metadata that mirrors Keycloak state — belongs in finmates-main |
| Keep `last_login` | Identity/security metadata, not in scope for profile-field drop |
| `followers_count`/`following_count` — no DB action | Were never materialized as columns; only computed in deleted SocialService/TraderController |
| Replace `/settings/privacy` entirely | New `GET/PUT /settings/notifications` handles only 6 notif_* fields |
| LeaderboardController — delete, not refactor | Leaderboard will be rebuilt in fm-social Prompt 7 from scratch |
| `spring.flyway.ignore-migration-patterns=*:missing` | V6/V7 files deleted but their flyway_schema_history rows retained; prevents false-positive migration mismatch error on startup |
| JWT claim key: `"sub"` not `"subject"` | Spring Security `Jwt.getClaims()` uses standard JWT claim names; old `JwtTokenService.extractTokenInfo()` had mapped it to `"subject"` |

---

## Post-Phase-B State

**finmates-main `users` table: 28 columns**
- Identity: `user_id`, `first_name`, `last_name`, `middle_name`, `username`, `email`, `phone_number`, `birth_date`
- Auth: `keycloak_id`, `provider`, `provider_id`, `email_verified`
- Security metadata: `two_factor_enabled`, `login_alerts_enabled`, `password_last_changed`
- Notifications: `notif_email`, `notif_push`, `notif_price_alerts`, `notif_news_alerts`, `notif_portfolio_updates`, `notif_friend_activity`
- Localisation: `timezone`, `verified`
- Soft delete: `is_active`, `deleted_at`
- Timestamps: `created_at`, `updated_at`, `last_login`

**Virtual balance**: now authoritative in `user_trading_state.virtual_balance` (crypto DB, V8).

**Social graph in finmates-main**: only `UserFriend`/`UserFriendId` (bidirectional friendship with `confirmed` flag) remains. All follow/social graph moved to fm-social.

---

## Backup Reference

Pre-Phase-B backups:
- `F:\backups\main-post-phase-a-2026-04-16.sql`
- `F:\backups\crypto-post-phase-a-2026-04-16.sql`
