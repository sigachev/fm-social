# Stage 1.3 — Legacy JJWT Pipeline Caller Audit

**Generated:** 2026-04-16  
**Investigator:** Claude Code Phase A  
**Scope:** All files in `F:\Projects\finmates-main` (src, test, resources, pom.xml)

---

## Summary

The legacy JJWT pipeline (`TokenProvider`, `JwtTokenService`, `JwtAuthenticationFilter`) is **actively wired** in the Spring Security configuration and OAuth2 success flow. All references are contained within the authentication subsystem. **No unexpected external callers were found.**

The `java-jwt` (Auth0) dependency is declared in pom.xml but is **never imported or used** anywhere in the codebase.

---

## Findings by Pattern

### 1. `TokenProvider`

| File | Classification | Notes |
|------|---------------|-------|
| `security/TokenProvider.java` | **Class definition** | `@Service`, uses `Jwts.builder()` + `HS512`. Methods: `createToken()`, `validateToken()`, `getUsernameFromToken()`, `getClaimsFromToken()` |
| `controller/AuthController.java` | Wiring inside legacy pipeline | `@Autowired TokenProvider tokenProvider` — used in auth controller |
| `security/oauth2/OAuth2AuthenticationSuccessHandler.java` | Wiring inside legacy pipeline | Injected via constructor — was likely the original OAuth2 token generator before `JwtTokenService` took over |
| `config/WebSecurityConfig.java` | Wiring inside legacy pipeline | Injected as constructor parameter |

### 2. `JwtTokenService`

| File | Classification | Notes |
|------|---------------|-------|
| `service/auth/JwtTokenService.java` | **Class definition** | `@Service`, dual-mode validation: custom JWT first, then Keycloak introspection fallback. Generates access + refresh tokens. |
| `controller/AuthController.java` | Wiring inside legacy pipeline | `@Autowired JwtTokenService jwtTokenService` — called for token generation on login |
| `security/jwt/JwtAuthenticationFilter.java` | Wiring inside legacy pipeline | `jwtTokenService.isTokenValid(token)` + `jwtTokenService.extractTokenInfo(token)` on every protected request |
| `security/oauth2/OAuth2AuthenticationSuccessHandler.java` | Wiring inside legacy pipeline | **Active OAuth2 flow** — generates access + refresh tokens on Google OAuth2 success: `jwtTokenService.generateAccessToken(email, name, registrationId)` |
| `config/auth/KeycloakProvider.java` | Documentation reference only | Comment: "rethrow so it can be caught by JwtTokenService" — no actual method call |

### 3. `JwtAuthenticationFilter`

| File | Classification | Notes |
|------|---------------|-------|
| `security/jwt/JwtAuthenticationFilter.java` | **Class definition** | `OncePerRequestFilter` — extracts Bearer token from Authorization header, calls `jwtTokenService.isTokenValid()` + `jwtTokenService.extractTokenInfo()` |
| `config/WebSecurityConfig.java` | Wiring inside legacy pipeline | Registered as a filter in the security chain: `http.addFilterBefore(jwtAuthenticationFilter, ...)` |

### 4. `app.auth.token-secret`

| File | Classification |
|------|---------------|
| `security/TokenProvider.java` | Class definition (`@Value("${app.auth.token-secret}")`) |
| `src/main/resources/application-dev.properties` (line 154) | Active dev configuration |
| `src/main/resources/application-k8s.properties` (line 119) | Active k8s configuration |
| `src/main/resources/application-aws.properties` (line 106) | Active AWS configuration |

### 5. `app.auth.token-expiration-msec`

| File | Classification |
|------|---------------|
| `security/TokenProvider.java` | Class definition (`@Value("${app.auth.token-expiration-msec}")`) |
| `src/main/resources/application-dev.properties` (line 155) | Active dev configuration |
| `src/main/resources/application-k8s.properties` (line 120) | Active k8s configuration |
| `src/main/resources/application-aws.properties` (line 107) | Active AWS configuration |

### 6. `jjwt` / `io.jsonwebtoken`

| File | Classification |
|------|---------------|
| `pom.xml` | Dependency declarations: `jjwt-api` (0.12.6), `jjwt-impl` (runtime), `jjwt-jackson` (runtime) |
| `security/TokenProvider.java` | Java imports: `Claims`, `Jwts`, `SignatureAlgorithm`, `Keys` |
| `service/auth/JwtTokenService.java` | Java imports: `ExpiredJwtException`, `*` (wildcard), `Keys` |
| `controller/AuthController.java` | Java import: `Claims` |

### 7. `Jwts.parser()`

| File | Lines | Context |
|------|-------|---------|
| `security/TokenProvider.java` | ~150, ~164, ~178 | `getUsernameFromToken()`, `validateToken()`, `getClaimsFromToken()` |
| `service/auth/JwtTokenService.java` | ~54, ~99, ~128 | `validateToken()`, `isTokenValid()`, `extractTokenInfo()` |

### 8. `java-jwt` / `com.auth0.jwt`

| File | Classification |
|------|---------------|
| `pom.xml` | **Declared** — `com.auth0:java-jwt:4.4.0` |
| *(no source files)* | **No imports found anywhere** — package is declared but completely unused |

---

## Unexpected External Usage

**NONE FOUND.**

All references to the legacy JJWT pipeline are within:
1. The class definitions themselves
2. Internal wiring between pipeline components (filter → service → provider)
3. The OAuth2 success handler (which is part of the auth subsystem)
4. The Spring Security configuration (also auth subsystem)

No business-logic services, domain controllers, or application features reference these classes outside the authentication subsystem.

---

## Architecture Note: Dual-Mode Authentication

The system currently implements two parallel JWT validation pathways simultaneously:

```
Incoming Bearer token
        │
        ▼
JwtAuthenticationFilter
        │
        ▼
JwtTokenService.isTokenValid(token)
   ├─► Try JJWT parse (custom token from JwtTokenService/TokenProvider)
   │       └─ If valid → authenticate, assign ROLE_USER
   └─► Try Keycloak introspection (KeycloakProvider.validateToken)
           └─ If valid → authenticate, extract Keycloak roles
```

This means the server currently accepts BOTH:
- Tokens it generated itself via JJWT (signed with `app.auth.token-secret`)
- Tokens issued by Keycloak (validated via JWKS introspection)

Phase B will collapse this to Keycloak-only (removing the JJWT path).

---

## Phase B Deletion Scope

These files and configs are safe to delete in Phase B, with no unexpected callers:

**Files to delete:**
- `security/TokenProvider.java`
- `service/auth/JwtTokenService.java`
- `security/jwt/JwtAuthenticationFilter.java`

**Wiring to remove from remaining files:**
- `controller/AuthController.java` — remove `TokenProvider` and `JwtTokenService` injections + usages
- `config/WebSecurityConfig.java` — remove `JwtAuthenticationFilter` injection + `addFilterBefore()` call
- `security/oauth2/OAuth2AuthenticationSuccessHandler.java` — replace `JwtTokenService.generateAccessToken()` call with Keycloak token issuance

**pom.xml:** Remove:
- `io.jsonwebtoken:jjwt-api`
- `io.jsonwebtoken:jjwt-impl`
- `io.jsonwebtoken:jjwt-jackson`
- `com.auth0:java-jwt` (unused — can be removed now in Phase A if desired, but no urgency)

**application-*.properties:** Remove from all three profile files:
- `app.auth.token-secret`
- `app.auth.token-expiration-msec`

---

## Risk Assessment for Phase B

| Component | Risk if deleted | Mitigation |
|-----------|----------------|-----------|
| `JwtAuthenticationFilter` | Existing JJWT tokens (issued by login flow) will stop being valid | All clients must re-authenticate after Phase B deploy; communicate token invalidation |
| `TokenProvider` | No live tokens reference this after filter is removed | Low risk |
| `JwtTokenService` | `OAuth2AuthenticationSuccessHandler` must be updated first to not generate tokens | Update handler BEFORE removing service |
| `app.auth.token-secret` | Secret rotation is safe once no code reads it | Remove from all properties files simultaneously |
| `java-jwt` (Auth0) | Unused — safe to remove any time | No action needed before Phase B |
