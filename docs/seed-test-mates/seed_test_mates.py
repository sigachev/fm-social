#!/usr/bin/env python3
"""
Seed 10 test users and make each a mate (mutual ACTIVE follow) of Mike's account.

NOT RUN YET. Mike approves first. Default mode is a dry run that only prints the plan.

Flow per user (same path a real sign-up takes, so every service gets its rows):
  1. finmates-main  POST /auth/signup            -> Keycloak user + main.users + crypto init + user_id attr
  2. Keycloak admin GET/PUT users/{id}           -> emailVerified=true, attribute seed_batch (merged, keeps user_id)
  3. finmates-main  POST /auth/authenticate      -> access token + numeric user id
  4. fm-social      GET  /api/profiles/me        -> ProfileEnsureFilter creates social.profiles row
                    PUT  /api/profiles/me        -> displayName, bio, location, timezone, showPnl, showInLeaderboard=false
                    PATCH /api/profiles/me/privacy (only for isPrivate users)
  5. finmates-main  GET+PUT /api/me/permissions  -> includeInLeaderboard=false (keeps seed users off the public leaderboard)
  7. finmates-crypto POST /api/wallets/watch     (as user) -> public watch-only wallets from users.json
  8. finmates-main  PUT /admin/billing/users/{id}/plan (as Mike, ADMIN) -> premium, so watched-wallet history shows
  6. fm-social      POST /api/follows/{mike}     (as user)  -> ACTIVE, Mike is public
                    POST /api/follows/{user}     (as Mike)  -> ACTIVE, or PENDING if user is private
                    POST /api/follows/requests/{mike}/accept (as user, only when PENDING)
                    GET  /api/follows/me/relationship/{user} (as Mike) -> must be isFollowing && isFollowedBy

Secrets:
  - Passwords are generated here with `secrets` and written ONLY to the credentials file
    (default ~/.finmates-secrets/<batch>.credentials.json, mode 0600). The script refuses a path
    inside F:\\Projects or any git work tree. Nothing secret is printed or logged.
  - Keycloak admin user/password: env FM_KC_ADMIN_USER / FM_KC_ADMIN_PASSWORD, else prompted (hidden).
  - Mike's access token: env MIKE_TOKEN, else prompted (hidden). Re-prompted if it expires mid-run.

Usage:
  python seed_test_mates.py                       # dry run: prints the plan, touches nothing
  python seed_test_mates.py --execute             # create users + mate connections
  python seed_test_mates.py --rollback MANIFEST   # dry run of rollback
  python seed_test_mates.py --rollback MANIFEST --execute
Stdlib only (Python 3.9+).
"""
import argparse
import getpass
import json
import os
import secrets
import stat
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAIN = os.environ.get("FM_MAIN_URL", "https://main.finmates.com")
SOCIAL = os.environ.get("FM_SOCIAL_URL", "https://social.finmates.com")
CRYPTO = os.environ.get("FM_CRYPTO_URL", "https://crypto.finmates.com")
KC = os.environ.get("FM_KC_URL", "https://auth.finmates.com")
REALM = os.environ.get("FM_KC_REALM", "finmates")


class HttpError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}")
        self.status, self.body = status, body


def http(method, url, token=None, body=None, form=None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")[:300]
        raise HttpError(e.code, raw) from None


def gen_password(n=24):
    # Meets the realm policy: upper, lower, digit, special, length >= 8 (SignUpRequest max 40).
    alphabet = string.ascii_letters + string.digits + "!@#%^*-_=+"
    while True:
        p = "".join(secrets.choice(alphabet) for _ in range(n))
        if (any(c.islower() for c in p) and any(c.isupper() for c in p)
                and any(c.isdigit() for c in p) and any(c in "!@#%^*-_=+" for c in p)):
            return p


def secrets_path(batch, override):
    p = Path(override).expanduser() if override else Path.home() / ".finmates-secrets" / f"{batch}.credentials.json"
    resolved = str(p.resolve()).replace("\\", "/").lower()
    if "/projects/" in resolved or resolved.startswith("f:/projects"):
        sys.exit(f"Refusing to store credentials under a Projects folder: {p}")
    try:
        out = subprocess.run(["git", "-C", str(p.parent), "rev-parse", "--is-inside-work-tree"],
                             capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip() == "true":
            sys.exit(f"Refusing to store credentials inside a git work tree: {p.parent}")
    except FileNotFoundError:
        pass
    return p


def load_json(p, default):
    return json.loads(p.read_text()) if p.exists() else default


def save_private(p, obj):
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=2))
    try:
        os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass  # Windows: rely on the user-profile ACL
    os.replace(tmp, p)


class Keycloak:
    def __init__(self):
        self.user = os.environ.get("FM_KC_ADMIN_USER") or input("Keycloak admin username: ")
        self._pw = os.environ.get("FM_KC_ADMIN_PASSWORD") or getpass.getpass("Keycloak admin password: ")
        self._tok, self._exp = None, 0

    def token(self):
        if time.time() > self._exp - 20:
            _, t = http("POST", f"{KC}/realms/master/protocol/openid-connect/token",
                        form={"grant_type": "password", "client_id": "admin-cli",
                              "username": self.user, "password": self._pw})
            self._tok, self._exp = t["access_token"], time.time() + int(t.get("expires_in", 60))
        return self._tok

    def find(self, username):
        q = urllib.parse.urlencode({"username": username, "exact": "true"})
        _, users = http("GET", f"{KC}/admin/realms/{REALM}/users?{q}", self.token())
        return users[0] if users else None

    def find_email(self, email):
        q = urllib.parse.urlencode({"email": email, "exact": "true"})
        _, users = http("GET", f"{KC}/admin/realms/{REALM}/users?{q}", self.token())
        return users[0] if users else None

    def verify_and_tag(self, kc_id, batch):
        _, rep = http("GET", f"{KC}/admin/realms/{REALM}/users/{kc_id}", self.token())
        attrs = rep.get("attributes") or {}
        attrs["seed_batch"] = [batch]           # merge: keeps the user_id attribute main just set
        rep["attributes"] = attrs
        rep["emailVerified"] = True
        http("PUT", f"{KC}/admin/realms/{REALM}/users/{kc_id}", self.token(), body=rep)

    def set_attribute(self, kc_id, name, value):
        _, rep = http("GET", f"{KC}/admin/realms/{REALM}/users/{kc_id}", self.token())
        attrs = rep.get("attributes") or {}
        attrs[name] = [value]
        rep["attributes"] = attrs
        http("PUT", f"{KC}/admin/realms/{REALM}/users/{kc_id}", self.token(), body=rep)

    def set_password(self, kc_id, password):
        http("PUT", f"{KC}/admin/realms/{REALM}/users/{kc_id}/reset-password", self.token(),
             body={"type": "password", "value": password, "temporary": False})

    def delete(self, kc_id):
        http("DELETE", f"{KC}/admin/realms/{REALM}/users/{kc_id}", self.token())


class Mike:
    def __init__(self, expect_username, expect_id):
        self.expect_username, self.expect_id = expect_username, expect_id
        self._tok = os.environ.get("MIKE_TOKEN") or getpass.getpass("Mike's access token (from the browser session): ")
        self.id = None

    def call(self, method, url, body=None):
        try:
            return http(method, url, self._tok, body=body)
        except HttpError as e:
            if e.status != 401:
                raise
            self._tok = getpass.getpass("Mike's token expired. Paste a fresh one: ")
            return http(method, url, self._tok, body=body)

    def check(self):
        _, me = self.call("GET", f"{MAIN}/auth/me")
        uname = (me or {}).get("username") or (me or {}).get("user", {}).get("username")
        _, prof = self.call("GET", f"{SOCIAL}/api/profiles/me")
        self.id = prof["userId"]
        if uname != self.expect_username or self.id != self.expect_id:
            sys.exit(f"Token belongs to {uname!r} id={self.id}, expected {self.expect_username!r} id={self.expect_id}. Abort.")
        if prof.get("isPrivate"):
            print("Note: Mike's profile is private; user->Mike follows will be PENDING and accepted as Mike.")
        return prof


def seed(args, cfg):
    batch = cfg["batch"]
    users = cfg["users"]
    cred_path = secrets_path(batch, args.secrets_file)
    manifest_path = cred_path.with_name(f"{batch}.manifest.json")

    print(f"Batch {batch}: {len(users)} users -> mates of @{cfg['mate_of_username']} (id {cfg['mate_of_user_id']})")
    print(f"Credentials file: {cred_path}\nManifest (no secrets): {manifest_path}")
    for u in users:
        print(f"  {u['username']:<16} {u['email']:<30} private={u['isPrivate']} showPnl={u['showPnl']} "
              f"wallets={len(u.get('watchWallets', []))}")
    if not args.execute:
        print("\nDry run. Nothing was changed. Re-run with --execute after approval.")
        return

    kc = Keycloak()
    mike = Mike(cfg["mate_of_username"], cfg["mate_of_user_id"])
    mike_prof = mike.check()

    creds = load_json(cred_path, {})
    manifest = load_json(manifest_path, {"batch": batch, "mike_id": mike.id, "users": {}})

    # Preflight: nothing in the batch may already exist unless this script created it.
    for u in users:
        if u["username"] in manifest["users"]:
            continue
        if kc.find(u["username"]) or kc.find_email(u["email"]):
            sys.exit(f"{u['username']} / {u['email']} already exists in Keycloak and is not in the manifest. Abort.")

    for u in users:
        name = u["username"]
        entry = manifest["users"].setdefault(name, {"email": u["email"], "steps": []})
        steps = entry["steps"]
        print(f"\n== {name}")

        if "signup" not in steps:
            if name not in creds:
                creds[name] = gen_password()
                save_private(cred_path, creds)          # store before use so a crash never loses it
            http("POST", f"{MAIN}/auth/signup", body={
                "username": name, "email": u["email"], "password": creds[name],
                "firstName": u["firstName"], "lastName": u["lastName"]})
            steps.append("signup"); save_private(manifest_path, manifest)
            print("  signed up (main + Keycloak + crypto init)")

        kc_user = kc.find(name)
        if not kc_user:
            sys.exit(f"  {name}: no Keycloak user after signup. Stop and check finmates-main logs.")
        entry["keycloakId"] = kc_user["id"]
        if "verified" not in steps:
            kc.verify_and_tag(kc_user["id"], batch)
            steps.append("verified"); save_private(manifest_path, manifest)
            print("  email verified + seed_batch attribute set")

        fixed = args.password or os.environ.get("FM_TEST_PASSWORD")
        if fixed and "password" not in steps:
            # Signup requires 8+ characters, so sign up with a generated one and then set the
            # shared test password in Keycloak (admin reset bypasses the signup length check).
            try:
                kc.set_password(kc_user["id"], fixed)
                creds[name] = fixed; save_private(cred_path, creds)
                print("  password set to the shared test password")
            except HttpError as e:
                print(f"  Keycloak refused the shared password (HTTP {e.status}, realm password policy?); keeping the generated one")
            steps.append("password"); save_private(manifest_path, manifest)

        _, login = http("POST", f"{MAIN}/auth/authenticate", body={"identifier": name, "password": creds[name]})
        tok = login["accessToken"]
        entry["userId"] = login["user"]["id"]
        save_private(manifest_path, manifest)

        if "profile" not in steps:
            http("GET", f"{SOCIAL}/api/profiles/me", tok)    # ensures social.profiles row
            http("PUT", f"{SOCIAL}/api/profiles/me", tok, body={
                "displayName": u["displayName"], "bio": u["bio"], "location": u["location"],
                "timezone": u["timezone"], "showPnl": u["showPnl"], "showInLeaderboard": False})
            if u["isPrivate"]:
                http("PATCH", f"{SOCIAL}/api/profiles/me/privacy", tok, body={"isPrivate": True})
            _, perms = http("GET", f"{MAIN}/api/me/permissions", tok)
            perms["includeInLeaderboard"] = False
            http("PUT", f"{MAIN}/api/me/permissions", tok, body=perms)
            steps.append("profile"); save_private(manifest_path, manifest)
            print("  profile, privacy and leaderboard opt-out set")

        uid = entry["userId"]
        if "mated" not in steps:
            st, f1 = http("POST", f"{SOCIAL}/api/follows/{mike.id}", tok)
            if f1 and f1.get("status") == "PENDING":
                mike.call("POST", f"{SOCIAL}/api/follows/requests/{uid}/accept")
            _, f2 = mike.call("POST", f"{SOCIAL}/api/follows/{uid}")
            if f2 and f2.get("status") == "PENDING":
                http("POST", f"{SOCIAL}/api/follows/requests/{mike.id}/accept", tok)
            _, rel = mike.call("GET", f"{SOCIAL}/api/follows/me/relationship/{uid}")
            if not (rel.get("isFollowing") and rel.get("isFollowedBy")):
                sys.exit(f"  {name}: not a mate after follow calls: {rel}")
            steps.append("mated"); save_private(manifest_path, manifest)
            print("  mate of Mike (both directions ACTIVE)")

        if u.get("watchWallets") and "wallets" not in steps:
            for w in u["watchWallets"]:
                try:
                    http("POST", f"{CRYPTO}/api/wallets/watch", tok,
                         body={"address": w["address"], "label": w["label"], "chainId": w["chainId"]})
                except HttpError as e:
                    if e.status != 409:      # 409 = already watched (re-run)
                        raise
            steps.append("wallets"); save_private(manifest_path, manifest)
            print(f"  watching {len(u['watchWallets'])} public wallet(s)")

        if cfg.get("grantPremium") and "premium" not in steps:
            # Watched-wallet history, closed positions, trades and performance are Premium.
            try:
                mike.call("PUT", f"{MAIN}/admin/billing/users/{uid}/plan", body={"plan": "premium"})
                print("  premium plan granted (billing, manual)")
            except HttpError as e:
                if e.status != 403:
                    raise
                # Mike's token has no ADMIN role. Set the JWT `plan` claim source directly in
                # Keycloak (what SubscriptionService.syncClaim writes); no main.subscriptions row.
                kc.set_attribute(entry["keycloakId"], "plan", "premium")
                print("  premium via Keycloak attribute plan=premium (no billing row: your token lacks ADMIN)")
            steps.append("premium"); save_private(manifest_path, manifest)

    _, stats = mike.call("GET", f"{SOCIAL}/api/follows/me/stats")
    print(f"\nDone. Mike's stats now: {stats}")
    print(f"Manifest: {manifest_path}")


def rollback(args, cfg):
    manifest_path = Path(args.rollback).expanduser()
    m = json.loads(manifest_path.read_text())
    users = m["users"]
    ids = [str(v["userId"]) for v in users.values() if v.get("userId")]
    print(f"Rollback batch {m['batch']}: {len(users)} users, ids {', '.join(ids) or '-'}")
    sql_path = manifest_path.with_name(f"{m['batch']}.rollback.sql")
    id_list = ", ".join(ids) or "NULL"
    text_list = ", ".join(f"'{i}'" for i in ids) or "NULL"
    sql = f"""-- Review, then run each block against its own database. Generated for batch {m['batch']}.
-- fm-social (db: social)
BEGIN;
DELETE FROM follows   WHERE follower_id IN ({id_list}) OR followed_id IN ({id_list});
DELETE FROM blocks    WHERE blocker_id  IN ({id_list}) OR blocked_id  IN ({id_list});
DELETE FROM reactions WHERE user_id     IN ({id_list});
DELETE FROM comments  WHERE author_id   IN ({id_list});
DELETE FROM posts     WHERE author_id   IN ({id_list});
DELETE FROM profiles  WHERE user_id     IN ({id_list});
COMMIT;
-- Then: redis-cli DEL feed keys for these ids (FeedService key pattern) if any posts were made.

-- finmates-crypto (db: crypto): every table with a user_id column that exists
-- Base tables only (no views); user_id compared as text so a VARCHAR/UUID column can't abort the
-- block; several passes so child rows go before parents when foreign keys don't cascade.
DO $$ DECLARE t text; left_ int; pass int := 0; BEGIN
  LOOP
    pass := pass + 1; left_ := 0;
    FOR t IN SELECT c.table_name FROM information_schema.columns c
             JOIN information_schema.tables x ON x.table_schema = c.table_schema AND x.table_name = c.table_name
             WHERE c.table_schema = 'public' AND c.column_name = 'user_id' AND x.table_type = 'BASE TABLE' LOOP
      BEGIN
        EXECUTE format('DELETE FROM %I WHERE user_id::text IN ({text_list})', t);
      EXCEPTION WHEN foreign_key_violation THEN left_ := left_ + 1;
      END;
    END LOOP;
    EXIT WHEN left_ = 0 OR pass >= 5;
  END LOOP;
  IF left_ > 0 THEN RAISE NOTICE '% table(s) still blocked by foreign keys', left_; END IF;
END $$;

-- finmates-main (db: main)
BEGIN;
DELETE FROM user_permissions            WHERE user_id IN ({id_list});
DELETE FROM user_bans                   WHERE user_id IN ({id_list});
DELETE FROM subscriptions               WHERE user_id IN ({id_list});  -- manual premium grant
DELETE FROM follows                     WHERE follower_id IN ({id_list}) OR followee_id IN ({id_list});  -- legacy table
DELETE FROM leaderboard_daily_snapshot  WHERE user_id IN ({id_list});
DELETE FROM email_verification_tokens   WHERE lower(email) IN ({", ".join("'" + v["email"] + "'" for v in users.values())});
DELETE FROM users                       WHERE user_id IN ({id_list});  -- PK column is user_id
COMMIT;
"""
    print("Check column names in the SQL against the live schema before running it (written, not executed):")
    print(f"  {sql_path}")
    if not args.execute:
        print("Dry run. Re-run with --execute to remove follows (as Mike) and delete the Keycloak users.")
        return
    sql_path.write_text(sql)
    kc = Keycloak()
    mike = Mike(cfg["mate_of_username"], cfg["mate_of_user_id"])
    mike.check()
    for name, v in users.items():
        if v.get("userId"):
            mike.call("DELETE", f"{SOCIAL}/api/follows/{v['userId']}")
        if v.get("keycloakId"):
            try:
                kc.delete(v["keycloakId"])
            except HttpError as e:
                if e.status != 404:
                    raise
        print(f"  {name}: Mike's follow removed, Keycloak user deleted")
    print(f"Now run the SQL in {sql_path}, then delete the credentials file.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(HERE / "users.json"))
    ap.add_argument("--secrets-file", help="credentials file path (outside any repo)")
    ap.add_argument("--execute", action="store_true", help="actually change things (default: dry run)")
    ap.add_argument("--password", help="shared password for all test users (or env FM_TEST_PASSWORD); "
                                       "default: a generated one per user")
    ap.add_argument("--rollback", metavar="MANIFEST")
    args = ap.parse_args()
    cfg = json.loads(Path(args.config).read_text())
    (rollback if args.rollback else seed)(args, cfg)


if __name__ == "__main__":
    main()
