# Seed 10 test mates for @sigachev

Status: plan only. Nothing has been created. Mike approves before anything runs.

Files:
- `docs/seed-test-mates/users.json`: the 10 users (names, usernames, @finmates.com emails, bios, privacy mix). No secrets.
- `docs/seed-test-mates/seed_test_mates.py`: the script. Python 3.9+, stdlib only. Dry run by default; `--execute` is required to change anything.

## Who

Mike = main user id 1, username `sigachev` (confirmed in finmates-main/CLAUDE.md, Phase 6 verification). The script checks this from Mike's token (`/auth/me` + `/api/profiles/me`) and stops if it doesn't match.

| Username | Name | Base | Style | Private | Shows P&L |
|---|---|---|---|---|---|
| priya.raman | Priya Raman | Toronto | ETH/L2 swing | no | yes |
| dan.okafor | Daniel Okafor | London | stablecoin yield | no | yes |
| sofia.mtz | Sofia Martinez | Madrid | Uniswap v3 LP | no | yes |
| kenji.w | Kenji Watanabe | Tokyo | BTC/ETH DCA | no | no |
| hannah.becker | Hannah Becker | Berlin | perps | yes | yes |
| marcus.reed | Marcus Reed | Chicago | BTC volatility | no | yes |
| aisha.khan | Aisha Khan | Dubai | Base early-stage | no | yes |
| lucas.oliveira | Lucas Oliveira | Sao Paulo | Arbitrum DeFi | no | yes |
| elena.petrova | Elena Petrova | Tbilisi | quant / bots | yes | no |
| tom.nguyen | Tom Nguyen | Sydney | research | no | yes |

Two private profiles and two with P&L hidden, so the Connections page and the planned friends' stats get tested against privacy, not only the happy path.

- Avatars: none at first. The UI's initials fallback covers it. Option: upload images later through `POST /api/uploads/presign/avatar` and then `PUT /api/profiles/me {avatarKey}`. Don't use third-party avatar URLs.
- Watch wallets: none. The earlier process (TEST_USERS_AND_PORTFOLIOS_REPORT.md) didn't add any, and putting a famous public address on a made-up person would look like a real holding. If wanted later, use `POST crypto /api/wallets/watch` with a label like "Watching: <name>".
- Leaderboard: every seeded user is opted out (`social showInLeaderboard=false` and `main includeInLeaderboard=false`), so real users never rank against fake ones.
- Tagging: the Keycloak attribute `seed_batch=test-mates-2026-11` makes the batch easy to find and roll back.

## How (order of calls)

This uses the same path as the April test users (Keycloak + main + profile), and goes through the public APIs so each service writes its own rows.

1. **Preflight**: confirm Mike's token is for sigachev / id 1. Confirm no username or email in the batch already exists in Keycloak. If one does, abort.
2. **Generate a password**: use `secrets` (24 chars, upper/lower/digit/special). Write it to the credentials file before it is used.
3. **`POST main /auth/signup`**: finmates-main creates the Keycloak user, the `main.users` row, the Keycloak `user_id` attribute, and the crypto user/wallet init. Side effect: a verification email is sent to each @finmates.com address, so those mailboxes need to exist or a catch-all has to absorb them.
4. **Keycloak admin**: `GET` then `PUT /admin/realms/finmates/users/{id}` sets `emailVerified=true` and adds `seed_batch`. The PUT merges attributes, so the `user_id` attribute is kept. Login is refused without the verified claim. UserSyncFilter copies the flag into main.
5. **`POST main /auth/authenticate`**: gets the user's token and numeric id.
6. **`GET social /api/profiles/me`**: ProfileEnsureFilter creates `social.profiles`. Then `PUT /api/profiles/me` sets displayName, bio, location, timezone, showPnl and showInLeaderboard=false. Then `PATCH /api/profiles/me/privacy` for the 2 private users.
7. **`GET` + `PUT main /api/me/permissions`**: sets includeInLeaderboard=false.
8. **Mate connection**:
   - As the user: `POST /api/follows/1`. Mike is public, so this is ACTIVE.
   - As Mike: `POST /api/follows/{user}`. This is ACTIVE, or PENDING for private users. If PENDING, the user accepts with `POST /api/follows/requests/1/accept`.
   - Check as Mike: `GET /api/follows/me/relationship/{user}` must return both isFollowing and isFollowedBy. Going through the API, not SQL, also fires the feed backfill.
9. A manifest with no secrets (username, email, keycloakId, userId, steps done) is saved after every step. A re-run resumes where it stopped.

After a run, Mike's stats should read: following +10, followers +10, mates +10.

## Secrets

- User passwords: kept only in `C:\finmates-secrets\test-mates-2026-11.credentials.json (via --secrets-file)` (mode 0600). The script refuses any path under F:\Projects or inside a git repo. Nothing secret is printed. Move them into the password manager afterwards if wanted.
- Keycloak admin: read from `FM_KC_ADMIN_USER` / `FM_KC_ADMIN_PASSWORD`, or a hidden prompt.
- Mike's token: read from `MIKE_TOKEN`, or a hidden prompt (copy the access token from the signed-in browser session). If it expires mid-run, the script prompts again.

## Run (after approval)

```
cd F:\Projects\fm-social\docs\seed-test-mates
python seed_test_mates.py              # dry run, prints the plan
python seed_test_mates.py --execute    # creates users + mates
```

## Rollback

```
python seed_test_mates.py --rollback ~/.finmates-secrets/test-mates-2026-11.manifest.json            # dry run
python seed_test_mates.py --rollback ~/.finmates-secrets/test-mates-2026-11.manifest.json --execute
```

1. Removes Mike's follow of each user (as Mike).
2. Deletes each Keycloak user.
3. Writes `test-mates-2026-11.rollback.sql` next to the manifest for `social`, `crypto` and `main`. The SQL is not run automatically: check the column names against the live schema, then run it. fm-admin's delete is a soft delete only, so it isn't enough here.
4. Delete the credentials file.

## Mike approves

1. Creating 10 users in the prod Keycloak realm and prod DBs, with the names and emails above.
2. Verification emails going to the 10 @finmates.com addresses.
3. Using a Keycloak admin login, ideally a dedicated service account rather than the master `admin`.
4. Using his own access token to follow the 10 users back.
5. Leaderboard opt-out, the 2 private / 2 hidden-P&L profiles, and no wallets or avatars in this round.

## Running it (as done on 2026-11-17)

- Keep the credentials outside any git repo. `C:\Users\sigac` is a git work tree, so use e.g.
  `--secrets-file C:\finmates-secrets\test-mates-2026-11.credentials.json` on every run (seed and rollback).
- Shared test password: `--password <pw>` or env `FM_TEST_PASSWORD` (signup needs 8+ chars, so the script
  signs up with a generated password and then sets the shared one through the Keycloak admin API).
  Never put it in `users.json`.
- Premium: tries `PUT /admin/billing/users/{id}/plan` with Mike's token; without the ADMIN role it sets the
  Keycloak attribute `plan=premium` instead (no `main.subscriptions` row).
- Watched wallets: public EOAs with open and closed Uniswap v3 positions, watch-only (`users.json`).
- Rollback: `--rollback <manifest> --secrets-file <same> --execute` removes Mike's follows and the Keycloak
  users, then writes SQL for social, crypto and main. Run it through the Postgres pod:
  `Get-Content -Raw file.sql | kubectl exec -i -n dev postgres-0 -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h localhost -U "$POSTGRES_USER" -d <db> -v ON_ERROR_STOP=1'`
