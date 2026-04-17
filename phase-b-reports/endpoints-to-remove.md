# Phase B — Stage 1: Endpoints to Remove Report

## Scope

Identify every HTTP endpoint in finmates-main that will be deleted (or partially modified)
as part of Phase B profile field removal. Verify no frontend code still calls these endpoints
before confirming deletion.

---

## Endpoints to DELETE Entirely

### `SettingsController`

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/settings/profile` | Returns current user's profile fields (bio, avatar, social handles) | **DELETE** |
| `PUT` | `/settings/profile` | Updates profile fields | **DELETE** |
| `POST` | `/settings/profile/avatar` | Uploads new avatar to S3 via `ImagesService` | **DELETE** |

**Impact:** After deletion, `ImagesService` has zero callers (it exists only to serve these endpoints).
`ImagesService` should be deleted in the same stage. `AmazonClient` is retained (used by `FileController`).

### `UserController`

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/user/profile/{username}` | Returns public profile view for a given username | **DELETE** |

**Note:** `UserController` may have other endpoints (e.g., `/user/friends`, `/user/{id}`) that are
NOT being deleted. Only the profile endpoint is removed. Verify remaining endpoints in `UserController`
before assuming the entire controller is deleted.

---

## Endpoints to PARTIALLY MODIFY

### `SettingsController` — `/settings/privacy`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/settings/privacy` | Returns privacy settings |
| `PUT` | `/settings/privacy` | Updates privacy settings |

**Current fields returned/updated:**
- `isPortfolioPublic` — **REMOVE** (now owned by `fm-social.profiles.portfolio_visibility`)
- `portfolioVisibility` — **REMOVE** (now owned by `fm-social.profiles.portfolio_visibility`)
- `showTradeHistory` — **REMOVE** (now owned by `fm-social.profiles.show_trade_history`)
- `showOnlineStatus` — **REMOVE** (now owned by `fm-social.profiles.show_online_status`)
- `showInLeaderboard` — **REMOVE** (now owned by `fm-social.profiles.show_in_leaderboard`)
- `allowFollowers` — **REMOVE** (now owned by `fm-social.profiles.allow_followers`)
- `allowMessages` — **REMOVE** (now owned by `fm-social.profiles.allow_messages`)
- `notif_push_*`, `notif_email_*`, `notif_*` fields (6 total) — **KEEP** (reserved for fm-notifications)

**Action:** After removing profile-related fields, these endpoints may effectively become
notification-only settings endpoints. If no non-profile settings remain in the response DTO,
consider whether these endpoints should be deleted entirely or retained as stubs for future
notification settings. If `notif_*` columns are present in `users`, the endpoints should be kept
with a trimmed DTO that only includes notification fields.

---

## `TraderController` Assessment

`TraderController` exposes public trader profile data for the social trading discovery feature.
It references:
- `getBio()`, `getProfileImage()`, `getThumbnail()`, `getLocation()`, `getWebsite()` — all being removed from `User.java`
- `SocialService.isFollowing()` — `SocialService` is being deleted
- `FollowRepository` — being deleted

**Options:**

| Option | Description |
|--------|-------------|
| A | Delete `TraderController` entirely — frontend trader cards are unsupported until fm-social wires profile API |
| B | Hollow out — remove profile field usages; return only `userId`, `username`, `email` with placeholder profile fields coming from a future fm-social call |
| C | Wire to fm-social internal profile API — read profile data from `fm-social` in same request |

**Recommendation: Option A (delete)** — `TraderController` is part of the deprecated profile
system. Frontend trader cards should be wired to `fm-social` profile endpoints directly. This avoids
partial implementations that will confuse future work.

---

## `LeaderboardController` Assessment

Currently calls `SocialService.getLeaderboard()` which computes rankings using:
- `user.getVirtualBalance()` — being removed
- `user.getIsPortfolioPublic()` — being removed
- `followersCount` / `followingCount` — being removed
- `SocialService` — being deleted

**Options:**

| Option | Description |
|--------|-------------|
| A | Delete `LeaderboardController` entirely — feature is owned by fm-social |
| B | Stub response — return empty list with `503 Service Unavailable` or feature flag |
| C | Wire to fm-social's internal leaderboard API (future prompt) |

**Recommendation: Option A (delete)** — The leaderboard is a social feature that should be computed
by fm-social (which has the follow graph, portfolio visibility settings, and profile data). The
finmates-main leaderboard is functionally broken anyway since it reads `virtualBalance` from users
(which will be removed) and fm-social's virtual balance is in `user_trading_state` in finmates-crypto.

---

## `AuthController` — `/auth/me` Modification (NOT deletion)

`/auth/me` is the core session endpoint — must be kept. The modification is:
- Remove field initializations: `setVirtualBalance`, `setIsPortfolioPublic`, `setProvider`, `setProviderId`
- The `UserDTO` returned by `/auth/me` will have fewer fields after `UserDTO` cleanup
- The endpoint itself is unaffected in routing and authentication behavior

---

## Summary Table

| Controller | Action | Notes |
|------------|--------|-------|
| `SettingsController` (profile) | Delete 3 endpoints | GET/PUT `/settings/profile`, POST `/settings/profile/avatar` |
| `SettingsController` (privacy) | Partial modify | Remove 7 profile-privacy fields; keep notif_* fields |
| `UserController` | Delete 1 endpoint | GET `/user/profile/{username}` only |
| `TraderController` | DELETE entire controller | All endpoints reference deleted fields/services |
| `LeaderboardController` | DELETE entire controller | Depends on SocialService + virtualBalance |
| `AuthController` | Modify only | Remove field sets in find-or-create; keep all endpoints |
| `ImagesService` | DELETE | Zero callers after SettingsController avatar endpoint removed |

---

## Frontend Impact

The following frontend components call endpoints being deleted. They will break after Phase B
unless updated to call fm-social instead. **Frontend wiring is NOT part of Phase B** — it is
deferred to a future prompt. These endpoints will be dead after Phase B.

| Frontend Component / Service | Endpoint Called | Status after Phase B |
|-----------------------------|----------------|---------------------|
| `ProfileSettingsPage` / `SettingsService` | `GET/PUT /settings/profile` | BROKEN — wire to `fm-social` |
| `AvatarUpload` component | `POST /settings/profile/avatar` | BROKEN — wire to `fm-social` |
| `TraderProfilePage` | `GET /user/profile/{username}` | BROKEN — wire to `fm-social` |
| `LeaderboardPage` | `GET /api/leaderboard` | BROKEN — wire to `fm-social` |
| `PrivacySettingsPage` | `GET/PUT /settings/privacy` (profile fields only) | Partial — notif fields still work |

These are known, accepted breakages. The frontend wiring prompt should follow Phase B.
