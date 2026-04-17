# CLAUDE.md — fm-social

## Before Starting Any Task
1. Read claude-mem session memory for prior decisions in this service
2. For architectural questions, check `graphify-out/GRAPH_REPORT.md` if present

## Service Purpose

`fm-social` is the **unified social layer** for the FinMates platform. It consolidates functionality
previously scattered across `finmates-main` (PortfolioNote, profile fields, Follow, Block) and
`finmates-crypto` (AssetComment, AssetCommentReaction) into a single, dedicated microservice.

**Domain coverage:**
- Posts (user-authored content with media, visibility, reactions)
- Polymorphic comments (POST / PORTFOLIO / ASSET target types)
- Crypto-native reactions (BULLISH, BEARISH, FIRE, DIAMOND_HANDS, REKT)
- Profile presentation (bio, avatar, social links, display name, visibility settings)
- Follow graph and block list
- Content moderation and user reports
- Edit audit trails

## Tech Stack

| Concern | Choice |
|---------|--------|
| Runtime | Java 21 |
| Framework | Spring Boot 3.3.5, Spring MVC (primary) |
| WebClient | Spring WebFlux (WebClient only — MVC is the HTTP server) |
| Database | PostgreSQL (`social` DB), Spring Data JPA, Hibernate |
| Migrations | Flyway (enabled, clean-slate — never manual DDL) |
| Cache | Spring Cache + Caffeine (in-memory), Redis (distributed feed/rate-limit) |
| Auth | Spring Security OAuth2 Resource Server, Keycloak JWT (JWKS) |
| Media | AWS SDK v2 S3 (keys stored, not URLs) — **Prompt 4** |
| Docs | springdoc-openapi-starter-webmvc-ui 2.6.0 |
| Lombok | `@Slf4j` OK everywhere; `@Getter @Setter` on JPA entities — NEVER `@Data` |

## Port

**8091** — never assume 8080 or any other port.

## Build & Run Commands

```bash
# Windows
.\mvnw.cmd clean compile          # compile check
.\mvnw.cmd spring-boot:run        # start with default (dev) profile

# Linux / Docker / Jenkins
./mvnw clean package -DskipTests  # build jar
./mvnw spring-boot:run            # start

# Run with specific profile
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

**Required env vars for local dev:**
```
DB_PASSWORD=<postgres password>
DB_USERNAME=finmates              # default in application-dev.yml
REDIS_HOST=localhost              # or port-forward redis.dev.svc.cluster.local
REDIS_PORT=6379
REDIS_PASSWORD=<from redis-auth secret>
INTERNAL_SHARED_SECRET=<32-char random string — must match other services>
```

Swagger UI: `http://localhost:8091/swagger-ui.html`
OpenAPI JSON: `http://localhost:8091/v3/api-docs`

## Database

- **DB name:** `social` (must be created manually before first start — see README.md)
- **Host (dev):** `finmates.com:5432`
- **Migration tool:** Flyway — all schema changes via `src/main/resources/db/migration/V*.sql`
- **JPA ddl-auto:** `validate` — Flyway owns the schema; Hibernate only validates

### Flyway Policy
- **Never** alter the schema manually in production
- **Never** modify an existing migration file — add a new `V{N+1}__description.sql`
- Migrations run automatically on startup
- `baseline-on-migrate: false` — `social` DB must be empty on first run

### Table Overview (as of V9)

| Table | Migration | Purpose |
|-------|-----------|---------|
| `posts` | V1 | User posts with media, visibility, reaction/comment counts |
| `comments` | V2 | Polymorphic comments (POST / PORTFOLIO / ASSET) with threading |
| `reactions` | V3 | Crypto-native reactions on posts and comments |
| `profiles` | V4 | Profile presentation extracted from finmates-main.users |
| `follows` | V5 | Unidirectional follow graph |
| `blocks` | V5 | Block list |
| `moderation_actions` | V6 | Immutable moderation audit log |
| `content_reports` | V7 | User-submitted content reports |
| `post_edits` | V8 | Post edit history (previous content) |
| `comment_edits` | V8 | Comment edit history (previous content) |
| `profiles` (extended) | V9 | Added privacy columns, OAuth avatar keys, name display preference |
| `profiles` (columns renamed) | V10 | `profile_image_key` → `avatar_key VARCHAR(500)`, `cover_image_key` → `cover_key VARCHAR(500)` |

## Implemented REST Endpoints (Prompt 3 — 2026-04-16)

22 endpoints across 6 controllers. All require a valid Keycloak JWT with the `user_id` custom claim
(added by `finmates-main` on `/auth/me`). See **JWT user_id Claim** section below.

### PostController — `/api/posts`
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/posts` | Create post |
| GET | `/api/posts/{id}` | Get post by ID |
| PATCH | `/api/posts/{id}` | Update post (5-min edit window) |
| DELETE | `/api/posts/{id}` | Soft-delete post |
| GET | `/api/posts/user/{userId}` | Get posts by user (paginated) |

### CommentController — `/api/comments`
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/comments` | Create comment on POST/PORTFOLIO/ASSET |
| GET | `/api/comments/post/{postId}` | List comments on a post |
| GET | `/api/comments/portfolio/{userId}` | List comments on a portfolio |
| GET | `/api/comments/asset/{symbol}` | List comments on an asset |
| PATCH | `/api/comments/{id}` | Update comment (5-min edit window) |
| DELETE | `/api/comments/{id}` | Soft-delete comment |

### ReactionController — `/api/reactions`
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/reactions` | Toggle reaction (insert if absent, delete if present) |
| GET | `/api/reactions/post/{postId}` | Reaction aggregate for a post |
| GET | `/api/reactions/comment/{commentId}` | Reaction aggregate for a comment |

### ProfileController — `/api/profiles`
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/profiles/me` | Get my profile |
| PUT | `/api/profiles/me` | Update my profile |
| GET | `/api/profiles/{userId}` | Get profile by user ID |
| GET | `/api/profiles/{username}/public` | **HTTP 501** — username→userId resolution deferred to Prompt 5 |

### FollowController — `/api/follows`
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/follows/{userId}` | Follow user |
| DELETE | `/api/follows/{userId}` | Unfollow user |
| GET | `/api/follows/me/following` | My following list |
| GET | `/api/follows/me/followers` | My follower list |
| GET | `/api/follows/me/relationship/{userId}` | My relationship to another user |
| GET | `/api/follows/{userId}/following` | Another user's following list |
| GET | `/api/follows/{userId}/followers` | Another user's follower list |

### BlockController — `/api/blocks`
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/blocks/{userId}` | Block user (also removes mutual follows) |
| DELETE | `/api/blocks/{userId}` | Unblock user |
| GET | `/api/blocks` | My block list |

## JWT `user_id` Claim Dependency

**All write endpoints and most read endpoints require a `user_id` claim in the JWT.**

`AuthenticatedUser.currentUserId()` reads `jwtToken.getClaim("user_id")` (a `Long`). This claim is
**not** present in standard Keycloak-issued tokens — it is added by `finmates-main` when a user
calls `GET /auth/me` (the find-or-create endpoint).

**Smoke test result (2026-04-16):** All auth-required endpoints return `HTTP 403` with
`"JWT missing user_id claim"` when tested with a raw Keycloak JWT (without the `user_id` claim).
This is expected behavior.

**Resolution (Prompt 5):** Add a Keycloak token mapper (or implement user ID resolution from the
JWT `sub` claim via a call to `finmates-main /api/internal/users/by-keycloak-id/{sub}`).

## S3 Media Integration (Prompt 4 — 2026-04-17)

### Bucket & folder structure

```
finmates-media  (us-east-1)
├── posts/_pending/user{id}/{uuid}.{ext}     ← initial client upload (presigned PUT)
├── posts/{postId}/{uuid}.{ext}              ← after post creation (copy from _pending)
├── users/{id}/profile/_pending/user{id}/{uuid}.{ext}  ← initial avatar upload
├── users/{id}/profile/{uuid}.{ext}          ← after profile update
├── users/{id}/cover/_pending/user{id}/{uuid}.{ext}    ← initial cover upload
└── users/{id}/cover/{uuid}.{ext}            ← after profile update
```

S3 lifecycle rule auto-deletes anything under `_pending/` after 1 day (safety net).

### Upload flow

1. Client → `POST /api/uploads/presign` (or `/presign/avatar`, `/presign/cover`) — gets presigned PUT URL
2. Client → PUT directly to S3 using presigned URL (fm-social never touches the bytes)
3. Client → `POST /api/posts` with `mediaKeys: ["posts/_pending/user42/uuid.jpg"]`
4. fm-social validates ownership → checks S3 existence → saves Post → copies to final path → updates Post → deletes `_pending/` copy

### Key S3 classes

| Class | Package | Purpose |
|-------|---------|---------|
| `S3Config` | `config` | `@Bean S3Client` + `@Bean S3Presigner` via `DefaultCredentialsProvider` |
| `S3Service` | `upload` | All S3 ops: `createPresignedPut`, `createPresignedGet`, `objectExists`, `copy`, `delete`, `validateOwnership` |
| `UploadController` | `upload` | `POST /api/uploads/presign` (batch), `/presign/avatar`, `/presign/cover`; Caffeine rate limiter |
| `PendingUploadsCleanupJob` | `upload` | `@Scheduled` 3 AM daily — observability only (counts stale objects, does NOT delete) |

### Raw S3 keys are never exposed in API responses

All response DTOs expose presigned GET URLs only (1h TTL, regenerated per request):
- `PostResponse.mediaUrls` — presigned GET URLs (was `mediaKeys`)
- `ProfileResponse.avatarUrl` — presigned S3 URL or OAuth avatar URL (was `profileImageKey`)
- `ProfileResponse.coverUrl` — presigned S3 URL (was `coverImageKey`)
- `ProfileResponse.thumbnailUrl` — presigned S3 URL or OAuth thumbnail URL

### Column renames (V10 migration)

`profiles.profile_image_key` → `avatar_key VARCHAR(500)` (same data, renamed for clarity)
`profiles.cover_image_key` → `cover_key VARCHAR(500)` (same data, renamed)
The V9 CHECK constraint `profile_image_exclusive` was dropped and recreated as `avatar_key_exclusive`.
`posts.media_keys` was already present from V1 — no change.

### Mutual exclusion (avatar S3 vs OAuth URL)

DB constraint `avatar_key_exclusive`: `CHECK (avatar_key IS NULL OR profile_image_url IS NULL)`.
- Setting `avatarKey` via S3 promotion automatically NULLs `profileImageUrl` in ProfileService.
- Setting `profileImageUrl` (OAuth) automatically NULLs `avatarKey` in ProfileService.applyUpdates.

### Local dev AWS setup

Add to IntelliJ run config or shell env:
```
AWS_ACCESS_KEY_ID=<your key>
AWS_SECRET_ACCESS_KEY=<your secret>
AWS_REGION=us-east-1
S3_BUCKET=finmates-media
```
**Never commit AWS credentials.** Without these, the context starts fine but any S3 call (presign, copy, exists) will fail with an `SdkClientException`.

### K8s AWS setup

Secret: `fm-aws-credentials` (namespace `dev`)
Keys: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
The secret is shared across services — fm-messaging and fm-notifications will use the same secret.
`deployment.yaml` injects all three keys plus `S3_BUCKET=finmates-media`.

### Rate limiting

`UploadController` uses a Caffeine cache (in-memory, not Redis) to rate-limit presign requests:
- Per-user counter, expires 1 minute after first request in the window
- Limit: 30 requests/min (configurable via `fm-social.s3.presign-rate-limit-per-minute`)
- Over-limit returns `HTTP 429` with `Retry-After: 60` header

### Endpoints added (Prompt 4)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/uploads/presign` | Batch presign PUT URLs for post/comment media (1–10 files) |
| POST | `/api/uploads/presign/avatar` | Single presign PUT URL for avatar upload |
| POST | `/api/uploads/presign/cover` | Single presign PUT URL for cover image upload |

### TODOs deferred to Prompt 5

- `thumbnailKey` in `ProfileUpdateRequest` is stored directly without S3 ownership validation or promotion. Full treatment (presign endpoint + promotion flow) deferred.
- `PATCH` rename: `ProfileUpdateRequest.profileImageKey` → `avatarKey`, `coverImageKey` → `coverKey`. Frontend must use the new field names.

---

## Deferred Features (future prompts)

| Feature | Prompt | Status |
|---------|--------|--------|
| Feed fan-out (Redis sorted sets) | Prompt 5 | Not started |
| Username→userId cross-service resolution (`/api/profiles/{username}/public`) | Prompt 5 | Stub returns 501 |
| thumbnailKey S3 promotion (upload endpoint + copy-on-update) | Prompt 5 | TODO |
| Moderation endpoints (admin) | Prompt 6 | Entities/migrations exist, no controllers |
| Frontend wiring | Prompt 7 | Not started |

## Service Dependencies

### Upstream (calls these services)
| Service | URL (dev) | URL (prod) | Purpose |
|---------|-----------|------------|---------|
| finmates-main | `http://localhost:8081` | `http://main` | User identity lookups (username, email) |
| finmates-crypto | `http://localhost:8087` | `http://crypto` | Asset/portfolio data for comment context |
| Keycloak | `https://auth.finmates.com` | same | JWT JWKS validation |
| Redis | `localhost:6379` (dev) | `redis.dev.svc.cluster.local:6379` | Feed sorted sets, rate limiting |

### Service-to-Service Auth
All outbound WebClient calls to finmates-main and finmates-crypto use the `X-Internal-Secret` header
(value from `INTERNAL_SHARED_SECRET` env var). Receiving services must validate this header on
`/internal/**` endpoints. The secret must match across all services in the same environment.

### Downstream (these services call fm-social)
- `finmates-front` — reads posts, comments, profiles, follow graph
- `fm-admin` — moderation endpoints (prompt TBD)

## Cross-Service Contracts (TBD in Prompt 5)

Internal endpoints that fm-social will expose for finmates-main and finmates-crypto:
- `GET /internal/v1/profiles/{userId}` — profile data for a user ID
- `GET /internal/v1/follows/count?userId=` — follower/following counts
- `POST /internal/v1/profiles` — create profile on new user registration

## Config Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `SecurityConfig` | `config` | Filter chain — stateless JWT, public paths; trust-all JwtDecoder for self-signed cert |
| `JwtAuthConverter` | `config.security` | Keycloak role extraction (realm_access + resource_access) |
| `JwtAuthConverterProperties` | `config.security` | `finmates.jwt.principal-attribute` + `resource-id` |
| `RedisConfig` | `config` | `RedisTemplate<String,String>` and `<String,Long>` beans |
| `WebClientConfig` | `config` | `mainServiceWebClient` + `cryptoServiceWebClient` beans |
| `CacheConfig` | `config` | Caffeine: userCache (5m), profileCache (2m), followGraphCache (5m) |
| `OpenApiConfig` | `config` | SpringDoc OpenAPI with JWT bearer security scheme |

## Lombok Conventions

```java
// JPA entities — NEVER @Data (breaks Hibernate proxy / lazy loading)
@Getter
@Setter
@NoArgsConstructor
public class Post { ... }

// Plain DTOs / value objects — @Data OK
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDto { ... }

// Logging — @Slf4j on any class
@Slf4j
@Service
public class PostService { ... }
```

## Image / Media Convention

S3 object **keys** are stored in the DB (`profile_image_key`, `media_keys` JSONB array), not full
URLs. Generate signed URLs or CloudFront URLs at read time. This keeps stored data stable even if
the CDN domain or bucket changes.

## Symbol Normalization

Asset symbols are always stored **uppercase**: `BTC`, `ETH`, `SOL`. Normalize on input:
`symbol.trim().toUpperCase()` before any DB write or Redis key construction.

## Known Gotchas

| Issue | Pattern |
|-------|---------|
| `@Data` on JPA entities | Breaks Hibernate proxy — use `@Getter @Setter` |
| Flyway + `ddl-auto=validate` | Schema must exist before startup — run migrations first |
| Redis pool exhaustion | `max-active: 16` in application.yml; increase if fan-out jobs saturate |
| WebClient timeout | 3-second timeout on all outbound calls — catch `TimeoutException` in service layer |
| `comments_target_exclusive` CHECK | Enforced at DB level — POST/PORTFOLIO use `target_id`, ASSET uses `target_symbol` |
| `reactions_unique` constraint | Toggle behavior: delete the row to "un-react"; re-insert to react again |
| Self-signed cert on `auth.finmates.com` | `SecurityConfig` defines a custom `@Bean JwtDecoder` with trust-all SSL, same pattern as `fm-admin`. Do NOT remove this override — Spring Boot's auto-configured decoder fails PKIX validation against the self-signed Keycloak cert. |
| `FmSocialApplicationTests.contextLoads` fails in CI | Context-loads test can't connect to DB. Needs test profile with Testcontainers or `@MockBean` JPA. Jenkinsfile uses `-DskipTests` to bypass for now. |
| `user_id` JWT claim required by all write endpoints | Standard Keycloak tokens lack `user_id`. All authenticated endpoints throw 403 until `finmates-main` adds the claim via a Keycloak token mapper (Prompt 5). |
| `@CacheEvict` in `ProfileService.updateProfile` | Calls `profileRepository.findById()` directly (not the cached `getProfile()`) to avoid Spring AOP self-invocation. `@CacheEvict` is intercepted by Spring proxy only when called from outside the bean (e.g., from controller). |
| `NameDisplayPreference` uses custom converter | DB stores lowercase `full_name`/`display_name`; enum is `FULL_NAME`/`DISPLAY_NAME`. Uses `NameDisplayPreferenceConverter` (manual `@Convert` on the field, NOT `autoApply = true`). |
| `Profile.userId` has no `@GeneratedValue` | Profile PK = user's ID from finmates-main. Do not add `@GeneratedValue` — the profile row is created with the user's ID, not auto-incremented. |
| `Comment.parentId` is a plain `Long`, not `@ManyToOne` | Avoids N+1 and lazy-load issues for threaded comments. |

## Deployment

- **Image:** `10.0.0.70:8090/fm-social:latest` (built from multi-stage `Dockerfile`)
- **Registry:** Nexus at `10.0.0.70:8090`
- **Namespace:** `dev`
- **Pipeline:** `Jenkinsfile` → `./mvnw clean package -DskipTests` → Docker → Nexus → `kubectl apply -f k8s/ -n dev`
- **K8s manifests:** `k8s/deployment.yaml`, `k8s/service.yaml`, `k8s/ingress.yaml` (created 2026-04-16)
- **Redis password:** `application-k8s.yml` has `REPLACE_AT_BUILD` placeholder — must be replaced with the value from K8s Secret `redis-auth` before first deploy
- **Port mapping:** container runs on **8080** (not 80 — port 80 requires root in the JVM container). `SERVER_PORT=8080` env var set in deployment.yaml. Service routes port 80 → targetPort 8080. Ingress routes to Service port 80 unchanged.

### K8s Resource Names (asymmetric by design)

| Resource | Name | Notes |
|----------|------|-------|
| Deployment | `social` | `kubectl rollout restart deployment/social -n dev` |
| Service | `social` | Internal cluster URL: `http://social.dev.svc.cluster.local` |
| Ingress | `social-ingress` | Distinguished from Service of the same bare name |

Public URL: `https://social.finmates.com`  
The Ingress backend still points to Service `social` (port 80) — the `social-ingress` name is for
the Ingress resource itself only.

## Persistent Context (claude-mem)

Cross-session memory via the `claude-mem` MCP plugin.

**Storage:** `C:/Users/user/.claude-mem/claude-mem.db` (SQLite)

### 3-layer search workflow
```
1. search("fm-social topic")       → index of matching IDs
2. timeline(anchor=ID)             → surrounding session context
3. get_observations([ID1, ID2])    → full detail for needed IDs
```
