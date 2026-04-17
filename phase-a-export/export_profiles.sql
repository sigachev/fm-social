\copy (
  SELECT
    user_id,
    bio,
    display_name,
    first_name,
    last_name,
    COALESCE(name_display_preference, 'display_name') AS name_display_preference,
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN profile_image LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(profile_image FROM 'https://s3\.us-east-1\.amazonaws\.com/finmates-images/(.*)')
      ELSE NULL
    END AS profile_image_key,
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN profile_image NOT LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        AND profile_image IS NOT NULL THEN profile_image
      ELSE NULL
    END AS profile_image_url,
    CASE
      WHEN cover_image LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(cover_image FROM 'https://s3\.us-east-1\.amazonaws\.com/finmates-images/(.*)')
      ELSE NULL
    END AS cover_image_key,
    CASE
      WHEN user_id = 1 THEN NULL
      WHEN thumbnail LIKE 'https://s3.us-east-1.amazonaws.com/finmates-images/%'
        THEN SUBSTRING(thumbnail FROM 'https://s3\.us-east-1\.amazonaws\.com/finmates-images/(.*)')
      ELSE NULL
    END AS thumbnail_key,
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
) TO 'F:\Projects\fm-social\phase-a-export\profiles-export.csv' WITH (FORMAT CSV, HEADER TRUE)
