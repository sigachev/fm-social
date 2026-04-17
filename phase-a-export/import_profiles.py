"""
Phase A — Stage 4: Import profiles CSV into fm-social.profiles.
All 17 rows are in the CSV (exported with defaults applied), so no separate seeding step needed.
"""
import csv
import psycopg2

SOCIAL_DB = dict(host="finmates.com", port=5432, dbname="social", user="user", password="Int6859351!")
CSV_FILE  = r"F:\Projects\fm-social\phase-a-export\profiles-export.csv"

INSERT_SQL = """
INSERT INTO profiles (
    user_id, bio, display_name, first_name, last_name, name_display_preference,
    profile_image_key, profile_image_url, cover_image_key,
    thumbnail_key, thumbnail_url,
    location, website, timezone,
    twitter_handle, discord_handle, telegram_handle, instagram_handle,
    facebook_handle, linkedin_handle, whatsapp_handle,
    profile_visibility, portfolio_visibility,
    show_pnl, show_positions, show_location, show_real_name, show_trading_activity,
    show_trade_history, show_online_status, show_in_leaderboard,
    allow_followers, allow_messages,
    notes_permission, signal_comments_permission, allow_copy_trading,
    created_at, updated_at
) VALUES (
    %(user_id)s, %(bio)s, %(display_name)s, %(first_name)s, %(last_name)s, %(name_display_preference)s,
    %(profile_image_key)s, %(profile_image_url)s, %(cover_image_key)s,
    %(thumbnail_key)s, %(thumbnail_url)s,
    %(location)s, %(website)s, %(timezone)s,
    %(twitter_handle)s, %(discord_handle)s, %(telegram_handle)s, %(instagram_handle)s,
    %(facebook_handle)s, %(linkedin_handle)s, %(whatsapp_handle)s,
    %(profile_visibility)s, %(portfolio_visibility)s,
    %(show_pnl)s, %(show_positions)s, %(show_location)s, %(show_real_name)s, %(show_trading_activity)s,
    %(show_trade_history)s, %(show_online_status)s, %(show_in_leaderboard)s,
    %(allow_followers)s, %(allow_messages)s,
    %(notes_permission)s, %(signal_comments_permission)s, %(allow_copy_trading)s,
    %(created_at)s, %(updated_at)s
)
ON CONFLICT (user_id) DO NOTHING
"""

def empty_to_none(val):
    """Convert empty CSV strings to None for nullable DB columns."""
    return None if val == "" else val

def main():
    conn = psycopg2.connect(**SOCIAL_DB)
    cur  = conn.cursor()

    # Verify profiles table is empty before import
    cur.execute("SELECT COUNT(*) FROM profiles")
    existing = cur.fetchone()[0]
    if existing > 0:
        print(f"WARNING: profiles table already has {existing} rows — inserting with ON CONFLICT DO NOTHING")

    inserted = 0
    skipped  = 0

    with open(CSV_FILE, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            # Normalize empty strings to None for nullable columns
            params = {k: empty_to_none(v) for k, v in row.items()}
            # Ensure user_id is int
            params["user_id"] = int(params["user_id"])

            # Execute and track
            cur.execute(INSERT_SQL, params)
            if cur.rowcount > 0:
                inserted += 1
            else:
                skipped += 1

    conn.commit()
    print(f"Import complete: {inserted} inserted, {skipped} skipped (conflict)")

    # Verify final count
    cur.execute("SELECT COUNT(*) FROM profiles")
    total = cur.fetchone()[0]
    print(f"Total profiles rows: {total}")

    # Spot-check key rows
    print("\nSpot-check:")
    cur.execute("""
        SELECT user_id, display_name, portfolio_visibility, allow_messages,
               profile_image_key, profile_image_url, thumbnail_key, thumbnail_url
        FROM profiles
        WHERE user_id IN (1, 4, 5, 7, 8, 9)
        ORDER BY user_id
    """)
    for r in cur.fetchall():
        uid, dname, pv, am, pk, pu, tk, tu = r
        print(f"  user_id={uid}: display_name={dname!r} portfolio_visibility={pv!r} "
              f"allow_messages={am!r} img_key={pk!r} img_url={pu!r}")

    # Verify no FRIENDS in portfolio_visibility
    cur.execute("SELECT COUNT(*) FROM profiles WHERE portfolio_visibility = 'FRIENDS'")
    friends_count = cur.fetchone()[0]
    if friends_count > 0:
        print(f"\nERROR: {friends_count} rows still have portfolio_visibility='FRIENDS'!")
    else:
        print("\nOK: No FRIENDS values in portfolio_visibility")

    # Verify all allow_messages are uppercase
    cur.execute("SELECT DISTINCT allow_messages FROM profiles")
    am_values = [r[0] for r in cur.fetchall()]
    print(f"Distinct allow_messages values: {am_values}")
    bad_am = [v for v in am_values if v and v != v.upper()]
    if bad_am:
        print(f"ERROR: lowercase allow_messages found: {bad_am}")
    else:
        print("OK: All allow_messages values are uppercase")

    # Verify CHECK constraint respected (no row has both key and url)
    cur.execute("""
        SELECT COUNT(*) FROM profiles
        WHERE profile_image_key IS NOT NULL AND profile_image_url IS NOT NULL
    """)
    violations = cur.fetchone()[0]
    if violations > 0:
        print(f"\nERROR: {violations} rows violate profile_image_exclusive constraint!")
    else:
        print("OK: profile_image_exclusive constraint satisfied on all rows")

    cur.close()
    conn.close()

if __name__ == "__main__":
    main()
