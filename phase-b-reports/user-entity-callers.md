# Phase B — Stage 1: User Entity Field Callers Report

## Scope

Identify every caller of the 36 `User.java` fields being dropped in Phase B, and determine
the action needed for each caller to avoid compilation failure after the field removal.

## Fields Being Removed (36 total)

**Profile presentation (14):**
`bio`, `displayName`, `profileImage`, `thumbnail`, `location`, `website`,
`twitterHandle`, `githubHandle`, `discordHandle`, `telegramHandle`,
`facebookHandle`, `instagramHandle`, `linkedinHandle`, `youtubeHandle`

**Image S3 storage (4):**
`profileImageKey`, `thumbnailKey`, `profileImageUrl`, `thumbnailUrl`

**Privacy / social settings (7):**
`isPortfolioPublic`, `portfolioVisibility`, `showTradeHistory`, `showOnlineStatus`,
`showInLeaderboard`, `allowFollowers`, `allowMessages`

**Virtual balance (1):**
`virtualBalance`

**Social graph counts (2):**
`followersCount`, `followingCount`

**Legacy portfolio (4):**
`portfolio`, `portfolioValue`, `portfolioReturn`, `lastPortfolioSnapshot`

**Legacy OAuth2 (2):**
`provider`, `providerId`

**Misc (2):**
`lastLogin`, `isPortfolioPublic` *(already counted above)*

> Note: exact field names may vary slightly from above — these are the logical groups.
> The authoritative list is the User.java entity (452 lines) as it exists today.

---

## Callers by Field Group

### `virtualBalance` Callers

| Class | Usage | Action |
|-------|-------|--------|
| `AuthController` | Sets `user.setVirtualBalance(new BigDecimal("10000"))` in `/auth/me` find-or-create | **Remove** — `user_trading_state` is the source of truth now |
| `AuthController` | Same in `/auth/me/token` find-or-create variant | **Remove** |
| `KeycloakAdminClientService` | Sets `virtualBalance` on user creation | **Remove** |
| `TradeService` | Reads `user.getVirtualBalance()` + `user.setVirtualBalance()` on buy/sell | **BLOCKER** — these are the deprecated paper trade endpoints; see decision below |
| `PortfolioService` | Reads `user.getVirtualBalance()` for portfolio init | **BLOCKER** — see decision below |
| `SnapshotJob` | `@Scheduled` — reads `user.getVirtualBalance()` for all users | **BLOCKER** — see decision below |
| `UserDTO` | Has `virtualBalance` field + mapping in `toUserDTO()` | **Remove field** + mapping line |

**Decision for TradeService / PortfolioService / SnapshotJob:**
These three classes implement the **deprecated virtual portfolio system** in finmates-main (the one
replaced by finmates-crypto's multi-portfolio system). After Phase B's column drop, they must not
compile against `User.virtualBalance`. Options:
1. **Delete** `TradeService`, `PortfolioService`, `SnapshotJob` in finmates-main (preferred — these
   are the old system's service layer, finmates-crypto owns portfolio now)
2. **Migrate** them to use `user_trading_state` via a cross-service call

**Recommended: Delete.** These are confirmed dead services — finmates-crypto's portfolio system
is the live implementation. Their controllers must also be evaluated (see `endpoints-to-remove.md`).

---

### Profile Presentation Fields (`bio`, `displayName`, `profileImage`, `thumbnail`, etc.)

| Class | Usage | Action |
|-------|-------|--------|
| `SettingsController` | GET/PUT `/settings/profile` — reads all 14 profile fields | **Delete endpoints** (see `endpoints-to-remove.md`) |
| `SettingsController` | POST `/settings/profile/avatar` — reads `profileImageKey`, `thumbnailKey` | **Delete endpoint** |
| `UserController` | GET `/user/profile/{username}` — reads profile fields for public view | **Delete endpoint** |
| `TraderController` | GET trader profile — reads `bio`, `profileImage`, `thumbnail`, `location`, `website` | **Delete or hollow out** |
| `AuthController` | `/auth/me` response includes `profileImage`, `displayName` via `toUserDTO()` | **Remove from DTO mapping** only; endpoint itself stays |
| `UserDTO` | Has all 14 profile fields + mapping in `toUserDTO()` | **Remove fields** + mapping lines |

---

### `isPortfolioPublic` / Privacy Fields

| Class | Usage | Action |
|-------|-------|--------|
| `AuthController` | Sets `isPortfolioPublic=true` in find-or-create | **Remove** |
| `KeycloakAdminClientService` | Sets `isPortfolioPublic=true` on user creation | **Remove** |
| `SocialService` | Reads `isPortfolioPublic` for leaderboard filtering | **Delete class** (SocialService is being deleted) |
| `TradeService` | Reads `isPortfolioPublic` for trade visibility checks | **Delete class** (deprecated service) |
| `UserDTO` | Has `isPortfolioPublic` field | **Remove field** |

---

### `provider` / `providerId` (OAuth2 legacy fields)

| Class | Usage | Action |
|-------|-------|--------|
| `AuthController` | Sets `user.setProvider("KEYCLOAK")` in find-or-create | **Remove** — provider tracking not needed post-OAuth2 deletion |
| `KeycloakAdminClientService` | May reference `provider` | **Remove** |
| `UserDTO` | May have `provider` field | **Remove field** |

---

### `lastLogin`

| Class | Usage | Action |
|-------|-------|--------|
| `AuthController` | `user.setLastLogin(LocalDateTime.now())` in `/auth/me` | **KEEP** — `lastLogin` is NOT a profile field; it belongs on the user record. Do NOT remove unless explicitly listed in the 36 fields. |

> **Verify** whether `lastLogin` is in the 36-field drop list. If it's in `users` DB as a column,
> it should stay. Confirm against `User.java` before touching.

---

### `followersCount` / `followingCount`

| Class | Usage | Action |
|-------|-------|--------|
| `SocialService` | Increments/decrements on follow/unfollow | **Delete class** (SocialService being deleted) |
| `UserDTO` | Has `followersCount`, `followingCount` | **Remove fields** — fm-social owns these counts now |
| `TraderController` | May display these counts | **Delete or hollow out** |

---

### Legacy Portfolio Fields (`portfolio`, `portfolioValue`, etc.)

| Class | Usage | Action |
|-------|-------|--------|
| `PortfolioService` (main) | Core usage | **Delete class** |
| `SnapshotJob` | Reads for daily snapshots | **Delete class** |
| `TradeService` (main) | Core usage | **Delete class** |
| `UserDTO` | Has portfolio fields | **Remove fields** |

---

## Classes Requiring Action (Summary)

| Class | Action | Notes |
|-------|--------|-------|
| `TradeService` (main) | **DELETE** | Deprecated portfolio service; finmates-crypto owns trades now |
| `PortfolioService` (main) | **DELETE** | Deprecated portfolio service |
| `SnapshotJob` (main) | **DELETE** | Uses `virtualBalance` + `portfolioValue` |
| `TraderController` | **DELETE or HOLLOW OUT** | Calls deleted SocialService + reads profile fields |
| `LeaderboardController` | **UPDATE** | Currently calls `SocialService` — needs new strategy or stub |
| `AuthController` | **MODIFY** — remove 5-6 field sets | Keep `/auth/me` endpoint; remove social/profile/balance field initializations |
| `KeycloakAdminClientService` | **MODIFY** — remove 2-3 field sets | Keep core Keycloak admin ops; remove `virtualBalance`/`isPortfolioPublic`/`provider` sets |
| `UserDTO` | **MODIFY** — remove ~34 fields | Keep auth/identity fields: `userId`, `username`, `email`, `firstName`, `lastName`, `middleName`, `emailVerified`, `createdAt`, `updatedAt`, `role`, `notif_*` fields |
| `SettingsController` | **DELETE 4 endpoints** + partial privacy | See `endpoints-to-remove.md` |
| `UserController` | **DELETE 1 endpoint** | `/user/profile/{username}` |
| `ImagesService` | **DELETE** | Orphaned after avatar endpoints removed |

---

## Fields to KEEP in `User.java` (do NOT remove)

| Field | Reason |
|-------|--------|
| `userId`, `username`, `email` | Core identity |
| `firstName`, `lastName`, `middleName` | Legal identity — explicit user instruction |
| `emailVerified` | Auth gate — Keycloak sets this |
| `role` | Authorization |
| `createdAt`, `updatedAt` | Audit |
| `lastLogin` | Session tracking |
| `notif_*` (6 columns) | Reserved for future fm-notifications service — explicit user instruction |
| `isActive` (if present) | Account state |

---

## `UserDTO` — 34-Field Cleanup

`toUserDTO()` currently has ~34 lines mapping the fields being removed. Each mapping line must be
deleted. The resulting DTO should contain only identity fields and fields explicitly kept above.
The `toUserDTO()` method must still compile and produce a coherent DTO for `/auth/me`.
