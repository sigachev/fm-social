"""
Phase A — Stage 3: Export profile data from finmates-main.users to CSV.
User 1 image fields are nulled (S3 path bug — to be fixed separately after AWS access restored).
"""
import sys
import csv

try:
    import psycopg2
except ImportError:
    print("psycopg2 not available, trying psycopg2-binary install hint")
    sys.exit(1)

CONN = dict(host="finmates.com", port=5432, dbname="main", user="user", password="Int6859351!")
OUT  = r"F:\Projects\fm-social\phase-a-export\profiles-export.csv"

SQL = """
SELECT
    user_id,
    bio,
    display_name,
    first_name,
    last_name,
    COALESCE(name_display_preference, 'display_name') AS name_display_preference,
    -- profile_image_key: S3 key extracted from URL; NULL for user 1 (path bug) and Google URLs
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN profile_image LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(profile_image FROM 'https://s3\\.us-east-1\\.amazonaws\\.com/finmates-images/(.*)')
      ELSE NULL
    END AS profile_image_key,
    -- profile_image_url: external (OAuth) URLs only; NULL for S3; NULL for user 1
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN profile_image NOT LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        AND profile_image IS NOT NULL THEN profile_image
      ELSE NULL
    END AS profile_image_url,
    -- cover_image_key
    CASE
      WHEN cover_image LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(cover_image FROM 'https://s3\\.us-east-1\\.amazonaws\\.com/finmates-images/(.*)')
      ELSE NULL
    END AS cover_image_key,
    -- thumbnail_key: NULL for user 1
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN thumbnail LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(thumbnail FROM 'https://s3\\.us-east-1\\.amazonaws\\.com/finmates-images/(.*)')
      ELSE NULL
    END AS thumbnail_key,
    -- thumbnail_url: external URLs only; NULL for user 1
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN thumbnail NOT LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        AND thumbnail IS NOT NULL THEN thumbnail
      ELSE NULL
    END AS thumbnail_url,
    location,
    website,
    timezone,
    twitter_handle,
    discord_handle,
    telegram_handle,
    instagram_handle,
    facebook_handle,
    linkedin_handle,
    whatsapp_handle,
    COALESCE(profile_visibility, 'PUBLIC') AS profile_visibility,
    CASE
      WHEN portfolio_visibility = 'FRIENDS' THEN 'FOLLOWERS'
      WHEN portfolio_visibility IS NULL      THEN 'PUBLIC'
      ELSE portfolio_visibility
    END AS portfolio_visibility,
    COALESCE(show_pnl, TRUE)             AS show_pnl,
    COALESCE(show_positions, TRUE)        AS show_positions,
    COALESCE(show_location, TRUE)         AS show_location,
    COALESCE(show_real_name, FALSE)       AS show_real_name,
    COALESCE(show_trading_activity, TRUE) AS show_trading_activity,
    COALESCE(show_trade_history, TRUE)    AS show_trade_history,
    COALESCE(show_online_status, TRUE)    AS show_online_status,
    COALESCE(show_in_leaderboard, TRUE)   AS show_in_leaderboard,
    COALESCE(allow_followers, TRUE)       AS allow_followers,
    UPPER(COALESCE(allow_messages, 'EVERYONE')) AS allow_messages,
    COALESCE(notes_permission, 'PUBLIC')           AS notes_permission,
    COALESCE(signal_comments_permission, 'PUBLIC') AS signal_comments_permission,
    COALESCE(allow_copy_trading, FALSE)   AS allow_copy_trading,
    COALESCE(created_at, NOW())           AS created_at,
    COALESCE(updated_at, NOW())           AS updated_at
FROM users
ORDER BY user_id
"""

def main():
    conn = psycopg2.connect(**CONN)
    cur  = conn.cursor()
    cur.execute(SQL)
    rows = cur.fetchall()
    cols = [d[0] for d in cur.description]

    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(cols)
        w.writerows(rows)

    print(f"Exported {len(rows)} rows to {OUT}")
    print("\nSpot-check:")
    for row in rows:
        uid = row[0]
        img_key = row[6]
        img_url = row[7]
        thumb_key = row[9]
        thumb_url = row[10]
        if img_key or img_url or thumb_key or thumb_url:
            print(f"  user_id={uid}: img_key={img_key!r} img_url={img_url!r} thumb_key={thumb_key!r} thumb_url={thumb_url!r}")

    cur.close()
    conn.close()

if __name__ == "__main__":
    main()
