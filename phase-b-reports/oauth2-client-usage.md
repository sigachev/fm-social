# Phase B — Stage 1: OAuth2 Client Usage Report

## Scope

Audit all usages of `spring-boot-starter-oauth2-client` across `finmates-main` to confirm
it is safe to remove the dependency after deleting the OAuth2 login subsystem.

---

## Classes Being Deleted (OAuth2 Login Subsystem)

| Class | Package | OAuth2 Client Usage |
|-------|---------|---------------------|
| `OAuth2AuthenticationSuccessHandler` | `security/oauth2/` | Core — extends `SimpleUrlAuthenticationSuccessHandler`, uses `OAuth2AuthorizedClientService` |
| `OAuth2AuthenticationFailureHandler` | `security/oauth2/` | Core — implements `AuthenticationFailureHandler`, uses `HttpCookieOAuth2AuthorizationRequestRepository` |
| `CustomOAuth2UserService` | `security/oauth2/` | Core — extends `DefaultOAuth2UserService` |
| `CustomAuthorizationRequestResolver` | `security/oauth2/` | Core — implements `OAuth2AuthorizationRequestResolver`, uses `DefaultOAuth2AuthorizationRequestResolver` |
| `HttpCookieOAuth2AuthorizationRequestRepository` | `security/oauth2/` | Core — implements `AuthorizationRequestRepository<OAuth2AuthorizationRequest>` |

All five of these classes import from `org.springframework.security.oauth2.client.*` and/or
`org.springframework.security.oauth2.core.*`. They are the sole OAuth2 client subsystem.

---

## Remaining Usages After Deletion

### `OAuth2Controller.java`

**Location:** `controller/OAuth2Controller.java`  
**Endpoint:** `@RestController` with `@RequestMapping("/oauth2")`

**Findings:**
- Has a field `@Autowired OAuth2AuthorizedClientService clientService` — **injected but never used in any method body**
- No method in the controller references `clientService`
- The controller may have other non-OAuth2 methods (e.g., the `initiateLogin` method that delegates to `OAuth2Service.initiateLogin()`)

**Decision:** Remove the `OAuth2AuthorizedClientService clientService` field (dead injection). Keep the controller if it has other active methods. If the controller exists solely for PKCE/redirect orchestration that lives in `OAuth2Service`, evaluate whether the controller itself survives.

**Import to remove:** `import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;`

### `OAuth2Config.java`

**Findings:**
- File exists but **entire content is commented out**
- No active beans, no active imports
- Safe to delete the file entirely

### `WebSecurityConfig.java` — `oauth2SecurityFilterChain`

**Findings:**
- Has a `@Bean @Order(2) oauth2SecurityFilterChain` method that configures `.oauth2Login(...)` with all 5 OAuth2 handler classes
- This filter chain must be deleted in its entirety
- The remaining filter chain `@Order(1)` (Keycloak JWT resource server) is the only production path

---

## Conclusion

| Item | Action |
|------|--------|
| 5 OAuth2 handler classes | DELETE |
| `OAuth2Config.java` | DELETE (already commented out) |
| `OAuth2Controller.java` unused `clientService` field | REMOVE field + import |
| `WebSecurityConfig.oauth2SecurityFilterChain` `@Bean` | DELETE |
| `spring-boot-starter-oauth2-client` in `pom.xml` | REMOVE |

**Zero unexpected external callers.** No other class outside the 5 deleted classes imports from
`org.springframework.security.oauth2.client.*` in any production-path code.

---

## Why the Spring OAuth2 Login Chain Is Dead Code

The frontend's "Sign in with Google" flow goes:
1. `OAuth2Service.initiateLogin('google')` → redirect to `auth.finmates.com` with `kc_idp_hint=google` + PKCE challenge
2. Keycloak brokers Google → redirects to `${FRONTEND_URL}/oauth2/callback?code=...`
3. `OAuth2Callback.tsx` calls `OAuth2Service.exchangeCode(code)` → POSTs to Keycloak token endpoint with PKCE verifier
4. Frontend calls `GET /auth/me` → finmates-main finds/creates user

**finmates-main's `/oauth2/authorization/google` endpoint is never called by the frontend.**
The Spring `oauth2SecurityFilterChain` has no live traffic. Confirmed by reading `AuthService.ts`
and tracing `initiateLogin` through the frontend.
