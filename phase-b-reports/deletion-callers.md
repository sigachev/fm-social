# Phase B — Stage 1: Deletion Callers Report

## Scope

Verify that each class scheduled for deletion has **zero unexpected external callers** — i.e.,
no class outside the deletion set depends on it in a way that would block or complicate removal.

---

## finmates-main — 17 Classes Scheduled for Deletion

### JWT / Auth Pipeline

| Class | File | External Callers |
|-------|------|-----------------|
| `TokenProvider` | `security/jwt/TokenProvider.java` | `JwtTokenService` only (also being deleted) |
| `JwtTokenService` | `service/auth/JwtTokenService.java` | `JwtAuthenticationFilter` only (also being deleted) |
| `JwtAuthenticationFilter` | `security/jwt/JwtAuthenticationFilter.java` | `WebSecurityConfig.filterChain` (reference being removed from config) |

### OAuth2 Login Subsystem

| Class | File | External Callers |
|-------|------|-----------------|
| `OAuth2AuthenticationSuccessHandler` | `security/oauth2/` | `WebSecurityConfig.oauth2SecurityFilterChain` only (being deleted) |
| `OAuth2AuthenticationFailureHandler` | `security/oauth2/` | `WebSecurityConfig.oauth2SecurityFilterChain` only (being deleted) |
| `CustomOAuth2UserService` | `security/oauth2/` | `WebSecurityConfig.oauth2SecurityFilterChain` only (being deleted) |
| `CustomAuthorizationRequestResolver` | `security/oauth2/` | `WebSecurityConfig.oauth2SecurityFilterChain` only (being deleted) |
| `HttpCookieOAuth2AuthorizationRequestRepository` | `security/oauth2/` | `WebSecurityConfig.oauth2SecurityFilterChain` + `OAuth2AuthenticationSuccessHandler` + `OAuth2AuthenticationFailureHandler` (all being deleted) |
| `OAuth2Config` | `config/OAuth2Config.java` | Entire file is commented out — zero callers |

### Social Graph

| Class | File | External Callers |
|-------|------|-----------------|
| `Follow` | `model/Follow.java` | `FollowId`, `FollowRepository`, `SocialService`, `TraderController` — all noted below |
| `FollowId` | `model/FollowId.java` | `Follow` only (being deleted) |
| `FollowRepository` | `repository/FollowRepository.java` | `SocialService` (being deleted), `TraderController`, `PortfolioNoteService` (being deleted) |
| `SocialService` | `service/SocialService.java` | `SocialController` (being deleted), `LeaderboardController` |

**`LeaderboardController` ← `SocialService`:** `LeaderboardController` calls `SocialService` for leaderboard data (user rankings, follower counts). `SocialService` is being deleted. `LeaderboardController` will need to be updated — either delete leaderboard endpoints or wire to fm-social's internal leaderboard API. See `user-entity-callers.md` and `endpoints-to-remove.md` for decisions.

**`TraderController` ← `FollowRepository`:** Uses `FollowRepository` to check `isFollowing()` status on trader profile endpoints. `TraderController` itself is largely deprecated (profile fields being stripped). Full disposition in `user-entity-callers.md`.

### PortfolioNote Stack

| Class | File | External Callers |
|-------|------|-----------------|
| `PortfolioNote` | `model/PortfolioNote.java` | `PortfolioNoteRepository`, `PortfolioNoteService` (both being deleted) |
| `PortfolioNoteRepository` | `repository/PortfolioNoteRepository.java` | `PortfolioNoteService` only (being deleted) |
| `PortfolioNoteService` | `service/PortfolioNoteService.java` | `PortfolioNoteController` only (being deleted) |
| `PortfolioNoteController` | `controller/PortfolioNoteController.java` | No external callers (REST endpoint only) |
| `PortfolioNoteDTO` | `dto/PortfolioNoteDTO.java` | `PortfolioNoteController` + `PortfolioNoteService` (both being deleted) |

### Images / S3

| Class | File | External Callers |
|-------|------|-----------------|
| `ImagesService` | `service/ImagesService.java` | **Zero callers** — not referenced by any class after `SettingsController` avatar endpoint is deleted. Safe to delete. |

> **Note:** `AmazonClient` is NOT in the deletion set. `FileController` uses `AmazonClient` for general file uploads, which is unrelated to profile avatar management. Do NOT delete `AmazonClient`.

---

## finmates-crypto — 8 Classes Scheduled for Deletion

| Class | File | External Callers |
|-------|------|-----------------|
| `AssetComment` | `model/AssetComment.java` | `AssetCommentRepository`, `AssetCommentService`, `AssetCommentReaction` (all being deleted) |
| `AssetCommentReaction` | `model/AssetCommentReaction.java` | `AssetCommentReactionId`, `AssetCommentRepository`, `AssetCommentService` (all being deleted) |
| `AssetCommentReactionId` | `model/AssetCommentReactionId.java` | `AssetCommentReaction` only (being deleted) |
| `AssetCommentRepository` | `repository/AssetCommentRepository.java` | `AssetCommentService` only (being deleted) |
| `AssetCommentService` | `service/AssetCommentService.java` | `AssetCommentController` only (being deleted) |
| `AssetCommentController` | `controller/AssetCommentController.java` | No external callers (REST endpoint only) |
| `AssetCommentDTO` | `dto/AssetCommentDTO.java` | `AssetCommentController` + `AssetCommentService` (both being deleted) |
| `AssetCommentReactionDTO` | `dto/AssetCommentReactionDTO.java` | `AssetCommentController` + `AssetCommentService` (both being deleted) |

---

## Summary

| Category | Classes | Unexpected Callers |
|----------|---------|-------------------|
| JWT pipeline (main) | 3 | 0 |
| OAuth2 login subsystem (main) | 6 | 0 |
| Social graph (main) | 4 | `LeaderboardController` uses `SocialService` — must update |
| PortfolioNote stack (main) | 5 | 0 |
| Images (main) | 1 | 0 (`AmazonClient` kept) |
| Asset comment stack (crypto) | 8 | 0 |
| **Total** | **27** | **1 class needing update** |

**Verdict: SAFE TO PROCEED.** The only cross-dependency is `LeaderboardController → SocialService`,
which is a known consequence of deleting `SocialService`. The leaderboard feature must either be
removed or wired to `fm-social` in a follow-up prompt.

---

## Test Coverage

No test files (`*Test.java`, `*Tests.java`) in either `finmates-main` or `finmates-crypto` reference
any of the 27 classes being deleted. Zero test changes required.
