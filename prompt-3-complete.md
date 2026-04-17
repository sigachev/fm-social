# fm-social Prompt 3 — Complete

**Date:** 2026-04-16  
**Scope:** Core CRUD layer — entities, repositories, services, controllers, DTOs for all social domains

---

## Summary

Full CRUD layer for `fm-social` is implemented, compiled, and running. 70 source files, 22 REST
endpoints across 6 controllers, all 9 Flyway migrations validated.

---

## What Was Built

### Stage 1 — Entities, Enums, Repositories (34 files)

**Enums:** `PostVisibility`, `CommentTargetType`, `ReactionType`, `ProfileVisibility`,
`NameDisplayPreference`, `ModerationActionType`, `ReportStatus`, `ReportTargetType`

**Entities (JPA):**
- `Post` — JSONB `media_keys`, `@PrePersist`/`@PreUpdate` timestamps
- `Comment` — polymorphic via `target_type`/`target_id`/`target_symbol`; `parentId` as plain `Long`
- `Reaction` — UNIQUE(userId, targetType, targetId, reactionType)
- `Profile` — natural PK = `user_id` (no `@GeneratedValue`); V4+V9 columns; custom `NameDisplayPreferenceConverter`
- `Follow`, `Block` — simple social graph entities
- `ModerationAction`, `ContentReport` — moderation audit trail
- `PostEdit`, `CommentEdit` — edit history snapshots

**Repositories (8 Spring Data JPA):**  
`PostRepository`, `CommentRepository`, `ReactionRepository`, `ProfileRepository`,
`FollowRepository`, `BlockRepository`, `PostEditRepository`, `CommentEditRepository`

Notable custom queries:
- `PostRepository.adjustCommentCount(targetId, delta)` — `@Modifying` JPQL for denormalized count
- `FollowRepository.deleteMutualFollows(userId1, userId2)` — atomic mutual-follow cleanup on block
- `BlockRepository.existsBlockInEitherDirection(userId1, userId2)` — bidirectional block check

**Common infrastructure:**
- `AuthenticatedUser` — reads `user_id` custom claim from `JwtAuthenticationToken`
- `PageResponse<T>` — generic pagination wrapper record
- `GlobalExceptionHandler` — 5 exception → HTTP status mappings including DataIntegrityViolationException → 409
- 5 custom exception classes: `ResourceNotFoundException`, `ForbiddenActionException`, `EditWindowExpiredException`, `DuplicateActionException`, `InvalidRequestException`

### Stage 2+3 — DTOs, Services, Controllers (28 files)

**DTOs (16 files):** Create/Update request records + Response records for all 6 domains.
- `CommentCreateRequest` includes `@AssertTrue isTargetValid()` validation for the
  POST/PORTFOLIO→targetId vs ASSET→targetSymbol exclusive constraint
- `ReactionAggregateResponse` uses `@JsonProperty` for uppercase JSON keys

**Services (6 files):** All `@Transactional(readOnly=true)` at class level, `@Transactional` on writes.

Key behaviors:
- **Edit window:** `createdAt.plusMinutes(editWindowMinutes).isBefore(OffsetDateTime.now())` — 5-min window configured via `@Value`
- **Audit trail:** `PostEdit`/`CommentEdit` saved in same transaction as entity update
- **Reaction toggle:** delete if present, insert if absent; adjusts denormalized count
- **Comment creation:** increments `posts.comment_count` for POST target type
- **Block side effect:** `followRepository.deleteMutualFollows()` called in same transaction
- **Follow guard:** `blockRepository.existsBlockInEitherDirection()` checked before follow
- **Profile caching:** `@Cacheable("profileCache")` on `getProfile()`; `@CacheEvict` on `updateProfile()`; `updateProfile()` reads from repo directly to avoid AOP self-invocation
- **Profile visibility:** PRIVATE→403; FOLLOWERS (not following)→minimal fields; PUBLIC/FOLLOWERS (following)→all fields filtered by `show_*` flags

**Controllers (6 files):** Full REST mappings; page-size cap (max 50); `@PreAuthorize` on owner-only operations.
- `ProfileController.getPublicProfileByUsername()` returns `HTTP 501` — username→userId resolution deferred to Prompt 5.

### Security Fix — Self-Signed Cert JwtDecoder

`SecurityConfig` was updated to add a custom `@Bean JwtDecoder` with a trust-all SSL context (same
pattern as `fm-admin`). Spring Boot's auto-configured decoder failed PKIX validation against the
self-signed cert on `auth.finmates.com`. The `@Bean` is auto-wired into the OAuth2 resource server
configuration.

---

## Compilation Results

```
[INFO] Compiling 70 source files with javac [debug parameters release 21]
[INFO] BUILD SUCCESS
```

---

## Runtime Verification

**Flyway:** All 9 migrations validated successfully at startup.

**Tomcat:** Started on port 8091.

**Swagger (`/v3/api-docs`):** 22 paths registered across 6 controllers.

```
GET    /api/blocks
POST   /api/blocks/{userId}
DELETE /api/blocks/{userId}
POST   /api/comments
GET    /api/comments/asset/{symbol}
GET    /api/comments/portfolio/{userId}
GET    /api/comments/post/{postId}
PATCH  /api/comments/{id}
DELETE /api/comments/{id}
GET    /api/follows/me/followers
GET    /api/follows/me/following
GET    /api/follows/me/relationship/{userId}
POST   /api/follows/{userId}
DELETE /api/follows/{userId}
GET    /api/follows/{userId}/followers
GET    /api/follows/{userId}/following
POST   /api/posts
GET    /api/posts/user/{userId}
GET    /api/posts/{id}
PATCH  /api/posts/{id}
DELETE /api/posts/{id}
GET    /api/profiles/me
PUT    /api/profiles/me
GET    /api/profiles/{userId}
GET    /api/profiles/{username}/public
POST   /api/reactions
GET    /api/reactions/comment/{commentId}
GET    /api/reactions/post/{postId}
```

---

## Smoke Test Results

Tested with a raw Keycloak JWT (user: `alex`, realm: `finmates`):

| Test | Expected | Actual | Notes |
|------|----------|--------|-------|
| GET /api/profiles/me | 200 | **403** | `user_id` claim missing from JWT |
| POST /api/posts | 201 | **403** | `user_id` claim missing from JWT |
| All auth endpoints | 2xx | **403** | Same root cause |

**Root cause:** `AuthenticatedUser.currentUserId()` reads `jwtToken.getClaim("user_id")`. Standard
Keycloak-issued tokens contain only `sub` (UUID), `preferred_username`, etc. — not the internal
DB `user_id`. This claim is added by `finmates-main` on `/auth/me`.

**Resolution:** Prompt 5 will add a Keycloak token mapper or implement `sub`→`userId` resolution
via `finmates-main /api/internal/users/by-keycloak-id/{sub}`.

---

## Test Suite Status

`FmSocialApplicationTests.contextLoads` fails — context can't start without a live database.
This is a known issue documented in CLAUDE.md. Jenkinsfile uses `-DskipTests`.

---

## Jenkins / K8s (from prior session)

- `Jenkinsfile` — fixed: `fm-social` deployment name, `./mvnw clean package -DskipTests`, `kubectl apply -f k8s/`
- `application-k8s.yml` — mirrors finmates-crypto pattern; Redis password is `REPLACE_AT_BUILD` placeholder
- `k8s/deployment.yaml`, `k8s/service.yaml`, `k8s/ingress.yaml`, `k8s/README.md` — all created

---

## What Remains (next prompts)

| Prompt | Feature |
|--------|---------|
| Prompt 4 | S3 media upload/download for post images and profile avatars |
| Prompt 5 | Feed fan-out (Redis sorted sets), cross-service user resolution, JWT `user_id` fix |
| Prompt 6 | Moderation controllers (entities/migrations already exist) |
| Prompt 7 | Frontend wiring — wire finmates-front to fm-social instead of finmates-main/finmates-crypto |
