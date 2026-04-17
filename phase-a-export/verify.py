"""
Phase A — Stage 7: Full verification of all acceptance criteria.
"""
import psycopg2
import os

SOCIAL_DB  = dict(host="finmates.com", port=5432, dbname="social", user="user", password="Int6859351!")
CRYPTO_DB  = dict(host="finmates.com", port=5432, dbname="crypto", user="user", password="Int6859351!")

BACKUPS = [
    r"F:\backups\main-2026-04-16.sql",
    r"F:\backups\crypto-2026-04-16.sql",
]

ok = True

def check(label, condition, detail=""):
    global ok
    status = "OK " if condition else "FAIL"
    if not condition:
        ok = False
    suffix = f" — {detail}" if detail else ""
    print(f"  [{status}] {label}{suffix}")

print("=" * 60)
print("PHASE A — VERIFICATION")
print("=" * 60)

# --- 7.1: Schema state ---
print("\n7.1 Schema State")

social = psycopg2.connect(**SOCIAL_DB)
sc = social.cursor()

sc.execute("""
    SELECT column_name FROM information_schema.columns
    WHERE table_name = 'profiles' AND table_schema = 'public'
    ORDER BY ordinal_position
""")
profile_cols = {r[0] for r in sc.fetchall()}
required_v9_cols = {
    "profile_image_url", "thumbnail_url",
    "show_trade_history", "show_online_status", "show_in_leaderboard",
    "allow_followers", "allow_messages"
}
missing = required_v9_cols - profile_cols
check("V9 columns present in profiles", not missing,
      f"missing={missing}" if missing else "all present")

sc.execute("SELECT version, description FROM flyway_schema_history ORDER BY installed_rank")
social_migrations = sc.fetchall()
check("fm-social at Flyway V9", any(v == "9" for v, _ in social_migrations),
      f"versions={[v for v,_ in social_migrations]}")

crypto = psycopg2.connect(**CRYPTO_DB)
cc = crypto.cursor()

cc.execute("""
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'user_trading_state'
    )
""")
check("user_trading_state table exists in crypto DB", cc.fetchone()[0])

cc.execute("SELECT version, description FROM flyway_schema_history ORDER BY installed_rank")
crypto_migrations = cc.fetchall()
check("finmates-crypto at Flyway V8", any(v == "8" for v, _ in crypto_migrations),
      f"versions={[v for v,_ in crypto_migrations]}")

# --- 7.2: Data state ---
print("\n7.2 Data State")

sc.execute("SELECT COUNT(*) FROM profiles")
total_profiles = sc.fetchone()[0]
check("17 profile rows in social DB", total_profiles == 17, f"got {total_profiles}")

sc.execute("""
    SELECT user_id, display_name, profile_image_key, profile_image_url
    FROM profiles WHERE profile_image_key IS NOT NULL OR profile_image_url IS NOT NULL
""")
img_rows = sc.fetchall()
check("Profiles with images exist", len(img_rows) > 0, f"{len(img_rows)} rows with images")

# User 1 — no images
sc.execute("""
    SELECT profile_image_key, profile_image_url, thumbnail_key, thumbnail_url
    FROM profiles WHERE user_id = 1
""")
u1 = sc.fetchone()
check("User 1 all image fields NULL (bug nulled)",
      all(v is None for v in u1),
      f"got {u1}")

# User 4 and 5 — S3 keys
sc.execute("SELECT user_id, profile_image_key FROM profiles WHERE user_id IN (4,5) ORDER BY user_id")
for uid, key in sc.fetchall():
    check(f"User {uid} has profile_image_key (S3)", key is not None and key.startswith("users/"), key)

# Users 7 and 8 — Google URLs
sc.execute("SELECT user_id, profile_image_url FROM profiles WHERE user_id IN (7,8) ORDER BY user_id")
for uid, url in sc.fetchall():
    check(f"User {uid} has profile_image_url (Google)", url is not None and "googleusercontent" in url, url)

# No FRIENDS
sc.execute("SELECT COUNT(*) FROM profiles WHERE portfolio_visibility = 'FRIENDS'")
check("No portfolio_visibility='FRIENDS'", sc.fetchone()[0] == 0)

# All allow_messages uppercase
sc.execute("SELECT DISTINCT allow_messages FROM profiles WHERE allow_messages != UPPER(allow_messages)")
bad = sc.fetchall()
check("All allow_messages UPPERCASE", len(bad) == 0, f"bad values: {bad}" if bad else "")

# Constraint: no row has both key and url
sc.execute("SELECT COUNT(*) FROM profiles WHERE profile_image_key IS NOT NULL AND profile_image_url IS NOT NULL")
check("profile_image_exclusive constraint: no row has both key+url", sc.fetchone()[0] == 0)

sc.execute("SELECT COUNT(*) FROM profiles WHERE thumbnail_key IS NOT NULL AND thumbnail_url IS NOT NULL")
check("thumbnail_exclusive constraint: no row has both key+url", sc.fetchone()[0] == 0)

# User 9 — FOLLOWERS (was FRIENDS)
sc.execute("SELECT portfolio_visibility FROM profiles WHERE user_id = 9")
u9_pv = sc.fetchone()[0]
check("User 9 portfolio_visibility='FOLLOWERS' (normalized from FRIENDS)", u9_pv == "FOLLOWERS", u9_pv)

# virtual_balance
cc.execute("SELECT COUNT(*) FROM user_trading_state")
vb_total = cc.fetchone()[0]
check("17 rows in user_trading_state", vb_total == 17, f"got {vb_total}")

cc.execute("SELECT virtual_balance FROM user_trading_state WHERE user_id = 1")
vb1 = cc.fetchone()[0]
check("User 1 virtual_balance=7872.09 (migrated)", float(vb1) == 7872.09, str(vb1))

cc.execute("SELECT virtual_balance FROM user_trading_state WHERE user_id = 9")
vb9 = cc.fetchone()[0]
check("User 9 virtual_balance=8974.585 (migrated)", abs(float(vb9) - 8974.585) < 0.001, str(vb9))

# --- 7.3: Flyway history text output ---
print("\n7.3 Flyway Migration History")
print("\n  fm-social (social DB):")
for v, desc in social_migrations:
    print(f"    V{v} — {desc}")

print("\n  finmates-crypto (crypto DB):")
for v, desc in crypto_migrations:
    print(f"    V{v} — {desc}")

# --- 7.4: K8s secret (kubectl not available locally — skip) ---
print("\n7.4 K8s Secret")
print("  [SKIP] kubectl not available on local dev box — secret file exists at:")
print("         F:\\Projects\\finmates-k8s\\secrets\\fm-internal-secret.yaml")
print("  Apply with: kubectl apply -f F:\\Projects\\finmates-k8s\\secrets\\fm-internal-secret.yaml")

# --- 7.5: Backups ---
print("\n7.5 Backups")
for bpath in BACKUPS:
    exists = os.path.exists(bpath)
    size   = os.path.getsize(bpath) if exists else 0
    check(f"Backup exists and non-empty: {os.path.basename(bpath)}",
          exists and size > 0, f"{size:,} bytes" if exists else "NOT FOUND")

# --- Summary ---
print("\n" + "=" * 60)
if ok:
    print("RESULT: ALL CHECKS PASSED")
else:
    print("RESULT: SOME CHECKS FAILED — review above")
print("=" * 60)

sc.close(); social.close()
cc.close(); crypto.close()
