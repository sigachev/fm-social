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
| Media | AWS SDK v2 S3 (keys stored, not URLs) |
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

### Table Overview (as of V8)

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

## Service Dependencies

### Upstream (calls these services)
| Service | URL (dev) | URL (prod) | Purpose |
|---------|-----------|------------|---------|
| finmates-main | `http://localhost:8081` | `http://finmates-main.dev.svc.cluster.local:8081` | User identity lookups (username, email) |
| finmates-crypto | `http://localhost:8087` | `http://finmates-crypto.dev.svc.cluster.local:8087` | Asset/portfolio data for comment context |
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
| `SecurityConfig` | `config` | Filter chain — stateless JWT, public paths |
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

## Deployment

- **Image:** built from multi-stage `Dockerfile` (eclipse-temurin:21-jdk-alpine → 21-jre-alpine)
- **Registry:** `10.0.0.70:8090/k8s-social`
- **Namespace:** `dev`
- **Pipeline:** `Jenkinsfile` → Build → Docker → Nexus → `kubectl apply -f deployment.yaml -n dev`
- **deployment.yaml:** to be created in a K8s prompt (not yet written)

## Persistent Context (claude-mem)

Cross-session memory via the `claude-mem` MCP plugin.

**Storage:** `C:/Users/user/.claude-mem/claude-mem.db` (SQLite)

### 3-layer search workflow
```
1. search("fm-social topic")       → index of matching IDs
2. timeline(anchor=ID)             → surrounding session context
3. get_observations([ID1, ID2])    → full detail for needed IDs
```
