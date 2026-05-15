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

### Table Overview (as of V17)

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
| `posts` (author_username) | V11 | Added `author_username VARCHAR(50)` — denormalized from JWT at post-creation time; null for pre-V11 posts |
| posts (backfill) | V12 | Backfilled author_username for pre-V11 posts via internal lookup |
| posts + comments (removal audit) | V13 | Added removed_at, removed_by, removal_reason for admin moderation |
| `reports` | V14 | User-submitted reports table — canonical moderation queue (dedup, rate-limited) |
| moderation tables dropped | V15 | Dropped unused `moderation_actions`, `content_reports` (superseded by `reports`) |
| follows + profiles (privacy) | V16 | Added `follows.status` (PENDING/ACCEPTED) and `profiles.is_private` for follow-request gating |
| comments (cashtag index) | V17 | Added `extracted_cashtags TEXT[]` + `comments_extracted_cashtags_gin` GIN index — populated on every write by `CashtagExtractor`; backs the Mentions tab query (Phase 2). See **Cashtag Extraction** below. |

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
| GET | `/api/posts/by-cashtag` | **Mixed-auth.** Recent posts tagged with a cashtag (token-detail social column). See section below. |

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
| GET | `/api/profiles/{username}/public` | Public profile by username — no auth required; resolves via `UserLookupCache` (5-min TTL) |
| GET | `/api/profiles/batch?ids=1,2,3` | **Batch profile summary lookup** — lightweight `{userId, displayName, avatarUrl}` for up to 200 users; intended for rendering avatars in comment/post lists; missing IDs omitted silently |

### Following Activity Feed — `/api/feed/following` (added 2026-04-30)

Sibling endpoint to the existing `/api/feed`. Surfaces a chronologically-ordered stream of `POSITION_OPENED` / `POSITION_CLOSED` events for users the viewer follows, with rolling-returns trader-performance metadata. Used by the dashboard's "Following Activity" widget.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/feed/following?limit=N` | Following-activity events; `limit` clamped to `[1, 50]`, default 20 |

**Response shape — `List<FollowingActivityEvent>`** (record in `feed/dto/`):
```
{
  eventId:    "{tradeId}-OPEN" | "{tradeId}-CLOSE",   // stable client React key
  userId:     Long,
  username:   String | null,                          // /u/:username navigation
  avatarUrl:  String | null,                          // S3 URL (null → initials badge)
  eventType:  "POSITION_OPENED" | "POSITION_CLOSED",
  symbol:     String,                                 // /token/:symbol navigation
  side:       "LONG" | "SHORT",                       // BUY/SELL normalized server-side
  qty:        BigDecimal,
  entryPrice: BigDecimal,
  exitPrice:  BigDecimal | null,                      // null for OPENED
  pnlPct:     BigDecimal | null,                      // null for OPENED; (realizedPnl / (entryPrice * qty)) * 100 for CLOSED
  occurredAt: Instant,
  traderPerf: { return1dPct, return1wPct, return1mPct }   // each field nullable — see contract below
}
```

**Pipeline** (in `FollowingFeedService`): follow-graph (`FollowRepository.findAllFollowingIds`) → cross-service trades fetch (`cryptoServiceWebClient` → `GET /api/internal/trades/recent?userIds=…&limit=N×2`) → expand each closed trade into both OPENED and CLOSED events → sort by `occurredAt DESC` → page-cap → batch hydrate (`ProfileService.getBatchSummaries` + `CryptoPerformanceClient.getRollingReturnsBatch`). The 2× trades-fetch accounts for the worst-case OPEN+CLOSE expansion ratio.

**Per-call cost ceiling**: 1 follow-graph query + 1 trades fetch + 1 profile-batch + 1 perf-batch = 4 cross-service-or-DB round trips per page, **regardless of `limit`**. The perf-batch fans out internally for cache-misses only; with both caches warm (5 min on the fm-social side, 24 h on the crypto side) it's an O(1) Caffeine lookup per event.

**`traderPerf` null-on-insufficient-data contract**: each of `return1dPct` / `return1wPct` / `return1mPct` can be null. A field is null when the subject user has no `user_wallet_snapshots` row at-or-before the window cutoff (e.g. `return1mPct` is null on a user with <30 days of history). **Do NOT collapse null to `BigDecimal.ZERO`** — same contract as `cashBalance` in finmates-crypto's `PortfolioService.buildFullDto` and `PortfolioSocialController.getSummaryWithSocial`. The frontend renders `—` for null and `0.00%` for zero, and conflating "no data" with "flat" is a meaningfully different lie that compounds across UI surfaces. The `BigDecimal.ZERO` literal bug shipped on 2026-04-30 in the dashboard's social card is the cautionary tale; the canonical comment block at `finmates-crypto/src/main/java/com/finmates/controller/PortfolioSocialController.java:320-332` is the rationale to cite. **See [ADR 0001](../docs/adr/0001-load-bearing-fields-null-on-uncertainty.md) for the canonical load-bearing fields policy** — `traderPerf.return*Pct` and the `cashBalance` triad are the two anchor cases that ADR codifies, and the `CryptoPerformanceClient` EMPTY-on-outage caching pattern below is cited there as the canonical retry-storm prevention shape.

**`CryptoPerformanceClient` — two-layer cache by design**:
- **fm-social side** (this service): manual Caffeine, 5-min TTL, 50k max entries. A freshness floor — protects the user from stale numbers when crypto's day-long cache hasn't naturally expired yet but real activity has happened in the followed set.
- **finmates-crypto side** (source of truth): Spring `@Cacheable("rollingReturns")`, 24-hour TTL, evicted at 00:05 UTC daily by `RollingReturnsCacheEvictionJob` (5 minutes after `PortfolioSnapshotJob` writes the day's snapshot row). This is where the actual cost-saving cache lives — rolling returns only change once a day.
- **The two TTLs are intentionally different.** Don't try to "synchronize" them: the short fm-social TTL is a freshness floor, the long crypto TTL is the cost-saving cache. If they were the same, fm-social would re-query crypto on every cold local-cache hit even when crypto already has the value cached. If fm-social cached as long as crypto, a cleared crypto cache would be masked by stale fm-social entries.
- **Outage handling**: `TraderPerf.EMPTY` (all-null sentinel) is returned **and cached** when crypto fails. This prevents a retry storm against a downed service — the next 5 minutes of requests for that user serve the cached EMPTY, then the cache expires and one request retries.

**Architectural note — why crypto data lives in crypto, not fm-social**: rolling returns intentionally cross the service boundary via HTTP rather than fm-social adding a cross-DB read against `crypto.user_wallet_snapshots`. Reasons: (1) preserves the documented "each service owns its DB" invariant — only `fm-admin` is multi-DB and that's tracked as a special-case complexity; (2) reuses the existing `cryptoServiceWebClient` plumbing pattern (`/api/internal/users/{userId}/rolling-returns` is the third sibling endpoint on `InternalCryptoSocialController` after `/positions/win-rate` and `/trades/recent`); (3) avoids granting fm-social Postgres privileges on the crypto database. The HTTP hop is cheap (cached for 24 h source-side, 5 min consumer-side).

**LAUNCH CAVEAT (2026-04-30)**: `crypto.user_wallet_snapshots` has only 7 days of accumulated history at deploy time (2026-04-23 to 2026-04-30, 4 distinct users). `return1d` works today; `return1w` works for users with ≥7 days; **`return1m` will be null for ALL users until 2026-05-23**. The em-dash render on the FE is correct — do not patch the backend or the FE to lie with zero. The data accumulates passively as the daily snapshot job continues to run.

**Cache-mode for tests** (`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`): set to `mock-maker-subclass` because Mockito's default inline mock-maker fails to instrument concrete services like `ProfileService` on Java 24 (the build target is Java 21 but local dev runs against JDK 24). Subclass-mode mocks work fine for everything fm-social tests do today; if you ever need to mock a `final` class or a static method, reconsider this setting.

### Token-Detail Cashtag Feed — `GET /api/posts/by-cashtag` (added 2026-05-13)

Companion to the token-detail social column on `/token/:symbol` in finmates-front. Powers the `<TokenFeedStrip symbol={...}/>` block. The frontend's `useTokenFeed(symbol)` consumes this contract.

**Query params**:
- `symbol` (required, String) — asset symbol, e.g. `BTC`. Normalized to uppercase server-side before matching.
- `scope` (optional, String) — `network` | `global`. Default is **JWT-presence-derived**: authenticated viewers default to `network` (posts from followed authors); anonymous viewers default to `global` (platform-wide). Logic lives in `PostController.resolveScope`, NOT pushed to the frontend.
- `limit` (optional, int) — clamped to `[1, 20]`, default 6.

**Auth**: mixed.
- Path is in `SecurityConfig.PUBLIC_PATHS` so `permitAll()` at the URL layer.
- `@PreAuthorize("permitAll()")` on the method overrides the class-level `@PreAuthorize("isAuthenticated()")` on PostController.
- Controller calls `AuthenticatedUser.currentUserIdOrNull()` (NOT `currentUserId()`) — the sibling helper added in this PR returns `null` instead of throwing when JWT is absent. **This is the canonical pattern for optional-auth endpoints in fm-social** — see javadoc on `currentUserIdOrNull` and prefer it over `@AuthenticationPrincipal(required = false)` or inline `SecurityContextHolder` access.
- `scope=network` with no JWT → **401 Unauthorized** (explicit `ResponseStatusException(HttpStatus.UNAUTHORIZED, ...)` thrown by the controller — NOT a `ForbiddenActionException` which would map to 403). The 401 surfaces because the URL is permitAll at the filter chain, so we have to enforce auth manually for the network branch.

**Cashtag matching strategy (State c — LIKE search)**:
- No `post_cashtags` join table exists.
- No tsvector full-text-search column on `posts`.
- Implementation: `LOWER(content) LIKE '%$<symbol>%'` against the un-indexed `posts.content` column in `PostRepository.findByCashtagGlobal` / `findByCashtagNetwork` (native PostgreSQL queries).
- The leading `$` anchors the match to cashtag-prefixed occurrences and prevents accidental substring hits (e.g. won't match `BTCUSD` in body text).
- **This is the agreed-upon scale-bounded mitigation.** It is OK for current scale (posts ≪ 50k rows). **A `post_cashtags` join table or a tsvector index should be added as follow-up work when the posts table grows past ~50k rows.** Track as a future migration; the LIKE plan is a sequential scan and will degrade.

**Sort**: engagement-weighted within last 24h, falling back to `createdAt` desc for older posts.
```sql
ORDER BY
  CASE WHEN created_at >= NOW() - INTERVAL '24 hours'
       THEN (reaction_count + comment_count * 2)
       ELSE 0
  END DESC,
  created_at DESC
```
JPQL doesn't express this cleanly across dialects, so the queries are native SQL — matching the existing `BlockRepository.existsBlockInEitherDirection` native-SQL precedent.

**Block filtering — scope-asymmetric by design**:
- `scope=network`: `BlockRepository.existsBlockInEitherDirection(viewerId, authorId)` is called per post in `PostService.filterBlocksAndMap`. Same bounded-N+1 pattern as `FeedService` for the main feed. Result size may be less than `limit` after filtering — over-fetch-to-compensate is intentionally NOT implemented; if it becomes a real UX problem, address then.
- `scope=global`: **no block filter applied.** Deliberate design trade-off:
  - Global is anonymous-friendly (no viewer to block from when there is no JWT).
  - Global is cacheable per `(symbol, limit)` — applying a per-viewer block filter would defeat the cache (every viewer needs a different filtered slice).
  - The trade-off is that an authenticated viewer hitting `scope=global` will see posts from authors they may have blocked. Acceptable for "trending across platform" — if they don't want that, they should hit `scope=network`.
  - **Do not "fix" this by applying the block filter to global.** It would silently turn a shared cache into a per-viewer cache, and would block the public/anonymous use case entirely.

**Caching — `scope=global` only**:
- Direct `RedisTemplate<String, String>` write (existing pattern in fm-social; no `@Cacheable` + RedisCacheManager bridge introduced).
- Key: `posts:by-cashtag:global:{NORMALIZED_SYMBOL}:{limit}`.
- Value: JSON-serialized `List<PostResponse>` via the default Spring Boot `ObjectMapper` bean.
- TTL: **2 minutes**. New posts surface after at most 2 minutes — no best-effort invalidation on `PostService.createPost` (the cost of cashtag-parsing every post to invalidate matching keys is not justified by a 2-minute staleness window).
- Cache read/write are best-effort: Redis outage logs a WARN and falls through to the DB path. Verification: in a Redis-down scenario the SQL log would show DB hits on every call AND a `Cashtag cache read failed for key=...` WARN line.
- `scope=network` is **NOT cached** — per-viewer, follow-graph-dependent, cache would be per-viewer-per-symbol-per-limit and amortize poorly.

**N+1 profile (DTO construction)**: same as main feed.
- 15 of 19 `PostResponse` fields come directly from `posts` row (denormalized `commentCount`/`reactionCount` included — populated by write-time adjusters in `CommentService`/`ReactionService`).
- `authorDisplayName` / `authorAvatarUrl` resolve through `PostService.authorCache` (Caffeine, 30s TTL, max 1000). Cold-cache worst case: ≤ `limit` profile lookups, single-row PK probes.
- `mediaUrls` and avatar URL: per-row `S3Service.createPresignedGet()` — local HMAC, no network or DB.
- No follow / visibility / ACL per-row service calls in `toResponse`.

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

The `user_id` claim is set directly on the Keycloak JWT via a protocol mapper — no call to
`finmates-main /auth/me` is required. Standard login tokens include the claim.

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
| Feed fan-out (Redis sorted sets) | Prompt 5 | **DONE** — `FeedService` + `FeedController` (`GET /api/feed`) |
| Username→userId cross-service resolution (`/api/profiles/{username}/public`) | Prompt 5 | **DONE** — `UserLookupCache` + `buildPublicProfileResponse()` |
| Profile auto-creation on first authenticated request | Prompt 5 | **DONE** — `ProfileEnsureFilter` + `ProfileInitializationService` |
| thumbnailKey S3 promotion (upload endpoint + copy-on-update) | Prompt 5 | TODO |
| Moderation foundation (Prompt 6a) | Prompt 6 | **DONE** â€” V13 removal columns, V14 reports table, ReportController/Service, internal remove/restore endpoints, InternalSecretFilter, UserBanCheckService |
| Frontend wiring | Prompt 7 | Not started |
| Testcontainers-backed test profile | post-Phase-2 | **TODO** — set up an `application-test.yml` + Testcontainers PG so integration tests can run without touching the shared dev DB at `finmates.com:5432`. Currently blocks repository slice tests for the Mentions tab (Phase 2) and any future `@SpringBootTest`. Until this lands, `./mvnw test` (bare) is unsafe and the Jenkinsfile uses `-DskipTests`. |
| Cashtag regex digit-prefix relaxation | post-launch | **TODO** — current regex `[A-Za-z][A-Za-z0-9]{0,14}` excludes digit-prefix tickers (`1INCH`, `00`, …). Acceptable for v1; relax to `[A-Za-z0-9][A-Za-z0-9]{0,14}` if usage data shows real demand. Requires synchronised changes to FE `mentionSegments.ts`, BE `CashtagExtractor`, and a new migration backfilling `comments.extracted_cashtags`. |

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

### Internal Follow API (CP5(c) Phase 1 — 2026-04-29)

`FollowsInternalController` at `/api/internal/follows/*`. Auth: `X-Internal-Secret`
header (validated by existing `InternalSecretFilter`). All endpoints fail-loud on
DB error (no fail-open) and return 200 with empty payload — never 404 — when a user
has no rows.

| Method | Path | Response | Repo backing |
|--------|------|----------|--------------|
| GET | `/api/internal/follows/mates?userId=X` | `Set<Long>` | `findAllMateIds` (NEW) |
| GET | `/api/internal/follows/mates?userId=X&include=profile` | `List<MateProfileEntry>` `{userId, username, displayName, avatarUrl}` | `findAllMateIds` + `ProfileService.getBatchSummaries` (Stage 12) |
| GET | `/api/internal/follows/following?userId=X` | `Set<Long>` | `findAllFollowingIds` (NEW) |
| GET | `/api/internal/follows/followers?userId=X` | `Set<Long>` | `findAllFollowerIds` (existing) |
| GET | `/api/internal/follows/followers-summary?userId=X` | `{totalCount, recent: [{userId, createdAt}]}` (recent capped at 50) | `countByFollowedIdAndStatus` + `findRecentFollowers` (NEW) |

**`?include=profile` variant** (Stage 12, May 2026): same auth + cache namespace
(`mates:profile:` key prefix) but joins server-side with `ProfileService.getBatchSummaries`
to avoid a second round-trip per mate. `username`, `displayName`, `avatarUrl` are
nullable per the underlying `ProfileSummaryResponse` contract — callers fall back to
initials. Routing uses Spring's `params={"!include"}` / `params="include=profile"`
discriminator on identical paths so the no-param shape stays `Set<Long>` for
back-compat with existing `FmSocialClient.getMateIds` callers.

Cache: `internal-follows` Caffeine cache, 60s TTL, 10k max entries; per-endpoint
key prefixes (`mates:` / `following:` / `followers:` / `summary:`) on a single shared
cache name. TTL aligns with the planned main-side `FmSocialClient` cache (cp5-c-design.md §2e).

Callers: planned in CP5(c) — finmates-main `FmSocialClient` (3 handler migrations behind
`finmates.fm-social.enabled` flag) and finmates-crypto `FmSocialClient` (mate-lookup
re-point). Currently no live callers — this is a dead-code release until step 2.

**Index coverage caveat:** `findAllMateIds` does a self-join on `(follower_id, status)`
and `(followed_id, status)`. EXPLAIN ANALYZE against staging is a prerequisite before
merge; if a sequential scan is detected, add composite indexes
`(follower_id, status, followed_id)` and the symmetric variant in a CP5-c-pre migration.

## Config Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `SecurityConfig` | `config` | Filter chain — stateless JWT, public paths; trust-all JwtDecoder for self-signed cert; registers `ProfileEnsureFilter` after `BearerTokenAuthenticationFilter` |
| `ProfileEnsureFilter` | `config` | Thin `OncePerRequestFilter` — fires after JWT validation; extracts `user_id` + display name from JWT, delegates to `ProfileInitializationService`; swallows all exceptions (never breaks a request) |
| `ProfileInitializationService` | `profile` | Service — auto-creates `social.profiles` row for authenticated users on first request; 60s Caffeine cache (50k max) to avoid per-request DB hits; race-safe via `DataIntegrityViolationException` swallow |
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


## Cashtag Extraction (Phase 1 — 2026-05-14)

`comments.extracted_cashtags TEXT[]` is populated on every comment write — both `createComment` and `updateComment`, both top-level and replies (`parent_id` is irrelevant to extraction). The column has a `DEFAULT '{}'` and is backed by `comments_extracted_cashtags_gin`. It feeds the Mentions tab query in Phase 2.

| Concern | Where |
|---|---|
| BE extractor | `com.finmates.social.util.CashtagExtractor` (pure static utility, no Spring) |
| FE extractor | `F:\Projects\finmates-front\src\components\mentions\mentionSegments.ts:18` |
| SQL backfill | `V17__cashtag_extraction.sql` |
| Unit tests | `CashtagExtractorTest` (pure JUnit) + `CommentServiceTest` (Mockito wiring) |

**The regex is duplicated in three places — keep them in lockstep.** All three must be:

```
(?<![A-Za-z0-9])\$([A-Za-z][A-Za-z0-9]{0,14})
```

If you change one, change all three atomically and re-run the manual FE/BE cross-check documented at the top of `CashtagExtractorTest`. The contract is: for every input string, FE `collectSegments()`, BE `CashtagExtractor.extract()`, and the V17 backfill subquery must produce the same canonical (uppercase, sorted, deduplicated) set of cashtags.

**Semantics:**
- Word-start enforcement via lookbehind — `$` must not be preceded by `[A-Za-z0-9]`.
- Symbol shape: first char is a letter, then 0–14 alphanumerics (1–15 chars total).
- Accepts any case in source (`$eth`, `$Eth`, `$ETH`) and canonicalises to uppercase.
- Empty / null input → empty list (never null). Replacement semantics on edit (the old list is overwritten, not merged).

**Known asymmetry — digit-prefix tickers do not match.** Assets whose canonical symbol begins with a digit (`1INCH`, `00`, etc.) cannot be extracted because the FE regex requires `[A-Za-z]` first. The BE mirrors this on purpose so the extracted set always equals the FE-rendered set. Users referencing these tokens in prose will not surface them in the Mentions tab. Tracked under **Deferred Features** below.

**Don't add Spring to the extractor.** Pure-function-as-static-utility is what lets the test suite run without a Spring context, which is what lets us avoid the broken `FmSocialApplicationTests` and the shared-dev-DB hazard documented in **Known Gotchas**.


## Moderation Architecture (Prompt 6a â€” 2026-04-17)

### Content Removal (Posts and Comments)

Soft-removal is already supported via `PostStatus.REMOVED` / `CommentStatus.REMOVED`. V13 adds audit columns so admins can see who removed content and why:

| Column | Type | Purpose |
|--------|------|---------|
| `removed_at` | TIMESTAMPTZ | When the content was removed |
| `removed_by` | BIGINT | Admin user ID who removed it |
| `removal_reason` | VARCHAR(500) | Free-text reason |

**User self-delete** sets `status=REMOVED` only (no audit fields â€” by design; user owns the action).
**Admin remove** calls `POST /api/internal/posts/{id}/remove` or `POST /api/internal/comments/{id}/remove`, which also sets `removed_at/by/reason`.

`FeedController` explicitly skips posts with `status=REMOVED` (line-level filter added in FeedController). All existing `findActive*` repository queries already exclude `REMOVED` via the `status = 'ACTIVE'` JPQL clause.

### User Reports

**User endpoint:** `POST /api/reports` â€” creates a report with dedup and rate-limiting.
**My reports:** `GET /api/reports/mine` â€” paginated list of own submissions.

**Business rules:**
- 1 report per (reporter, target_type, target_id) â€” duplicate returns HTTP 409
- Max 10 reports per user per 24 hours â€” over-limit returns HTTP 429
- Cannot report your own posts, comments, or yourself
- Target must exist (post or comment validated; USER existence deferred to ban time)

**Package:** `com.finmates.social.report` â€” `Report`, `ReportRepository`, `ReportService`, `ReportController`, `ReportInternalController`, and all enums.

**Enums:** `ReportTargetType` (POST, COMMENT, USER), `ReportReason` (SPAM, HARASSMENT, HATE_SPEECH, MISINFORMATION, MARKET_MANIPULATION, INAPPROPRIATE_CONTENT, SCAM_OR_FRAUD, IMPERSONATION, OTHER), `ReportStatus` (PENDING, REVIEWED, DISMISSED), `ResolutionAction` (CONTENT_REMOVED, USER_WARNED, USER_SUSPENDED, USER_BANNED, NO_ACTION).

### Internal Endpoints (Moderation)

All `/api/internal/**` paths are `permitAll()` in SecurityConfig but gated by `InternalSecretFilter` (validates `X-Internal-Secret` header — timing-safe comparison against `finmates.internal.shared-secret`). No JWT required.

#### Inter-service auth: shared secret

- The `X-Internal-Secret` header pattern protects every `/api/internal/**` endpoint in fm-social. Spring Security `permitAll`s those paths; `InternalSecretFilter` is the actual gate.
- fm-social and every caller (today: fm-admin via `FmSocialClient`, finmates-main via internal clients) MUST be configured with the same `INTERNAL_SHARED_SECRET` in non-dev profiles.
- **Dev profile** falls back to `dev-local-secret` on both sides — never deploy this value.
- **k8s / prod profiles** fail fast at startup: `InternalSecretValidator` (`com.finmates.social.config`) throws `IllegalStateException` from `@PostConstruct` if `finmates.internal.shared-secret` is blank or equals `dev-local-secret` while the active profile is one of `k8s`, `prod`, `production`, `aws`. Spring Boot will refuse to start.
- On every successful startup the validator logs `Internal shared secret configured (length=N, profile=X)` at INFO. The secret value is NEVER logged — only its length.
- On a 401 from `InternalSecretFilter`, response body now includes `"hint":"X-Internal-Secret missing or mismatched"` and the filter logs a WARN with the request path + remote address (no secret values). When debugging 401s on `/api/internal/**`, check both services' startup logs for the `Internal shared secret configured (length=N)` line and confirm the lengths match.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/internal/reports` | Admin report queue (filter by status) |
| GET | `/api/internal/reports/{id}` | Single report |
| PUT | `/api/internal/reports/{id}/resolve` | Resolve a report (REVIEWED / DISMISSED) |
| PUT | `/api/internal/posts/{id}/remove` | Admin-remove a post with audit trail |
| PUT | `/api/internal/posts/{id}/restore` | Restore a removed post |
| PUT | `/api/internal/comments/{id}/remove` | Admin-remove a comment with audit trail |
| PUT | `/api/internal/comments/{id}/restore` | Restore a removed comment |

### Ban Check at Write Time

`UserBanCheckService` (package `com.finmates.social.moderation`) is called in `PostController.createPost()` and `CommentController.createComment()` before any write. It calls `finmates-main` at `GET /api/internal/users/{userId}/ban-status` via the `mainServiceWebClient` with a 60-second Caffeine cache (5000 max entries).

- PERMANENT ban or active SUSPENSION â†’ HTTP 403 "Your account has been suspended or banned"
- Network errors â†’ fail-open (check skipped, write proceeds) to prevent ban-service outages blocking all social activity
- Invalidate cache: `userBanCheckService.invalidate(userId)` after an unban
## SQL visibility in dev profile

`application-dev.yml` sets `org.hibernate.SQL: DEBUG` and `org.hibernate.type.descriptor.sql.BasicBinder: TRACE`. This is **on by default in dev** and is intentional, not a temporary diagnostic toggle.

**Why**: every controller PR that touches data access must be eyeballed against the Hibernate SQL log before commit. N+1 patterns (per-row queries against profiles, blocks, follows, etc.) are not visible in unit tests or static review — they only surface as repeated SELECTs with different bind args in the SQL log. PR verification workflows for endpoints like `GET /api/feed`, `GET /api/posts/by-cashtag`, etc. depend on this.

**What "bounded N+1" means here**: a per-row query whose count is capped by the request's `limit` parameter (≤ 20-50) and whose individual cost is a sub-millisecond indexed PK lookup. Bounded N+1 against `profiles` (author cache cold-miss) and `blocks` (`existsBlockInEitherDirection` per post) is the accepted pattern in this service — it mirrors the main feed and is amortized by Caffeine. **What to watch for instead**: unbounded outer-correlated queries, COUNT subqueries firing per row, or fan-out queries against tables that should have been part of the initial batch SELECT.

If you ever want to silence the SQL log temporarily during a long-running dev session (e.g. profiling a hot path), override locally via `JAVA_TOOL_OPTIONS=-Dlogging.level.org.hibernate.SQL=INFO` — do NOT commit a change to `application-dev.yml` that downgrades the level.

## Known Gotchas

| Issue | Pattern |
|-------|---------|
| `@Data` on JPA entities | Breaks Hibernate proxy — use `@Getter @Setter` |
| Flyway + `ddl-auto=validate` | Schema must exist before startup — run migrations first |
| Redis pool exhaustion | `max-active: 16` in application.yml; increase if fan-out jobs saturate |
| WebClient timeout | 3-second timeout on all outbound calls — catch `TimeoutException` in service layer |
| `comments_target_exclusive` CHECK | Enforced at DB level — POST/PORTFOLIO use `target_id`, ASSET uses `target_symbol` |
| `reactions_unique` constraint | Toggle behavior: delete the row to "un-react"; re-insert to react again |
| `PostResponse.authorUsername` may be null | Posts created before V11 migration have no `author_username` stored. Frontend falls back to `user_${authorId}` display when null. New posts always have it set from JWT `preferred_username`. |
| `PostResponse` / `CommentResponse` must include moderation fields (`removedAt`, `removalReason`, `removedBy`) | Fixed 2026-04-19. The DTOs previously omitted these three columns even though they exist on `Post` / `Comment` entities (added in V13). Frontend `PostCard.tsx` computes `isRemoved = post.removedAt !== null` — if the field is absent from the JSON, it becomes `undefined` client-side, `undefined !== null` is `true`, and the "removed by moderator" banner renders on EVERY post. Any future response DTO that mirrors an entity with moderation columns MUST surface `removedAt` / `removalReason` / `removedBy` and pass them through in `toResponse()`. |
| `PublicProfileResponse.userId` added (2026-04-17) | Frontend needs `userId` from public profile to call `GET /api/posts/user/{userId}` in the Posts tab. Added as first field in the record; `ProfileService.buildPublicProfileResponse` passes `targetUserId`. |
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

## Claude Code tooling

This repo is indexed by Graphify at `F:\Projects\graphify-out\`. The post-commit hook (installed via `graphify hook install`) auto-rebuilds the AST graph on every commit — no LLM cost, ~1–3 s.

Before starting complex refactors, query the graph for dependency impact:
```bash
graphify query "<search term>"                       # BFS traversal of graph.json
graphify query "<search term>" --dfs --budget 4000  # DFS with higher token budget
```

Hook management:
```bash
cd F:/Projects/fm-social
graphify hook status    # verify hook is installed
graphify hook install   # reinstall if missing
```

Full workspace re-index (all 7 services at once, from monorepo root):
```bash
cd F:/Projects && graphify update .
```

## Persistent Context (claude-mem)

Cross-session memory via the `claude-mem` MCP plugin.

**Storage:** `C:/Users/user/.claude-mem/claude-mem.db` (SQLite)

### 3-layer search workflow
```
1. search("fm-social topic")       → index of matching IDs
2. timeline(anchor=ID)             → surrounding session context
3. get_observations([ID1, ID2])    → full detail for needed IDs
```

- PR-1 (fm-social internal-follows API): branch pushed
  feat/cp5-c-internal-follows-api, NOT merged. Gate 0
  (EXPLAIN ANALYZE on findAllMateIds) DEFERRED — must run against
  staging before merge. Hand-verify query correctness in same
  staging session. Cache-hit test and @DataJpaTest deferred as
  follow-up cleanup tasks.