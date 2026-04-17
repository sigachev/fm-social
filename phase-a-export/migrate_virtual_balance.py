"""
Phase A — Stage 5: Migrate virtual_balance from finmates-main.users
into finmates-crypto.user_trading_state for all 17 users.

Step 1: Fetch all user_ids + virtual_balances from main DB.
Step 2: Export to CSV as audit trail.
Step 3: Insert into user_trading_state in crypto DB
        (ON CONFLICT DO NOTHING — idempotent).
Final count must be 17.
"""
import csv
import psycopg2
from decimal import Decimal

MAIN_DB   = dict(host="finmates.com", port=5432, dbname="main",   user="user", password="Int6859351!")
CRYPTO_DB = dict(host="finmates.com", port=5432, dbname="crypto", user="user", password="Int6859351!")
CSV_FILE  = r"F:\Projects\fm-social\phase-a-export\virtual-balance-export.csv"

def main():
    # --- Step 1: Fetch from main DB ---
    main_conn = psycopg2.connect(**MAIN_DB)
    main_cur  = main_conn.cursor()
    main_cur.execute("""
        SELECT user_id, COALESCE(virtual_balance, 10000) AS virtual_balance
        FROM users
        ORDER BY user_id
    """)
    rows = main_cur.fetchall()
    main_cur.close()
    main_conn.close()

    print(f"Fetched {len(rows)} rows from main.users")
    non_default = [(uid, bal) for uid, bal in rows if bal != Decimal("10000")]
    print(f"Non-default balances ({len(non_default)} users):")
    for uid, bal in non_default:
        print(f"  user_id={uid}: virtual_balance={bal}")

    # --- Step 2: Write CSV audit trail ---
    with open(CSV_FILE, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerows(rows)
    print(f"\nCSV written to {CSV_FILE}")

    # --- Step 3: Insert into user_trading_state ---
    crypto_conn = psycopg2.connect(**CRYPTO_DB)
    crypto_cur  = crypto_conn.cursor()

    # Verify table exists
    crypto_cur.execute("""
        SELECT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_name = 'user_trading_state'
        )
    """)
    if not crypto_cur.fetchone()[0]:
        raise RuntimeError("user_trading_state table does not exist — V8 migration may not have applied")

    inserted = 0
    skipped  = 0
    for user_id, virtual_balance in rows:
        crypto_cur.execute("""
            INSERT INTO user_trading_state (user_id, virtual_balance, created_at, updated_at)
            VALUES (%s, %s, NOW(), NOW())
            ON CONFLICT (user_id) DO NOTHING
        """, (user_id, virtual_balance))
        if crypto_cur.rowcount > 0:
            inserted += 1
        else:
            skipped += 1

    crypto_conn.commit()
    print(f"\nInserted {inserted} rows, {skipped} skipped (conflict)")

    # Verify final count
    crypto_cur.execute("SELECT COUNT(*) FROM user_trading_state")
    total = crypto_cur.fetchone()[0]
    print(f"Total user_trading_state rows: {total}")

    if total != 17:
        print(f"ERROR: Expected 17 rows, got {total}!")
    else:
        print("OK: All 17 users have user_trading_state rows")

    # Show all rows
    crypto_cur.execute("SELECT user_id, virtual_balance FROM user_trading_state ORDER BY user_id")
    all_rows = crypto_cur.fetchall()
    print("\nAll user_trading_state rows:")
    for uid, bal in all_rows:
        marker = " <-- non-default" if bal != Decimal("10000") else ""
        print(f"  user_id={uid}: virtual_balance={bal}{marker}")

    crypto_cur.close()
    crypto_conn.close()

if __name__ == "__main__":
    main()
