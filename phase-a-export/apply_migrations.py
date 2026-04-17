"""
Phase A — Stage 2 (execution): Apply V8 (crypto DB) and V9 (social DB) Flyway migrations.
Also inserts correct entries into flyway_schema_history so Flyway won't re-run them on next startup.

Flyway checksum = standard CRC32 (signed 32-bit) of UTF-8 file content,
  line endings normalized to LF, BOM stripped.
"""
import struct
import zlib
import datetime
import psycopg2

CRYPTO_DB = dict(host="finmates.com", port=5432, dbname="crypto", user="user", password="Int6859351!")
SOCIAL_DB  = dict(host="finmates.com", port=5432, dbname="social", user="user", password="Int6859351!")

V8_FILE = r"F:\Projects\finmates-crypto\src\main\resources\db\migration\V8__create_user_trading_state.sql"
V9_FILE = r"F:\Projects\fm-social\src\main\resources\db\migration\V9__add_missing_profile_columns.sql"


def flyway_checksum(filepath: str) -> int:
    """Compute Flyway SQL migration checksum: signed CRC32 of LF-normalized UTF-8 content."""
    with open(filepath, "r", encoding="utf-8-sig") as f:  # utf-8-sig strips BOM
        content = f.read()
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    raw = zlib.crc32(normalized.encode("utf-8")) & 0xFFFFFFFF
    # Convert to signed 32-bit integer (Flyway stores signed)
    if raw >= 0x80000000:
        raw -= 0x100000000
    return raw


def apply_migration(conn_params: dict, sql_file: str, version: str, description: str):
    with open(sql_file, "r", encoding="utf-8") as f:
        sql = f.read()

    checksum = flyway_checksum(sql_file)
    now = datetime.datetime.utcnow()

    conn = psycopg2.connect(**conn_params)
    conn.autocommit = False
    cur = conn.cursor()

    # Check if already applied
    cur.execute("SELECT version FROM flyway_schema_history WHERE version = %s", (version,))
    if cur.fetchone():
        print(f"  [SKIP] V{version} already in flyway_schema_history — skipping")
        cur.close()
        conn.close()
        return

    # Apply the migration SQL
    print(f"  Applying V{version}...")
    try:
        cur.execute(sql)
    except psycopg2.Error as e:
        conn.rollback()
        cur.close()
        conn.close()
        raise RuntimeError(f"Migration V{version} SQL failed: {e}") from e

    # Insert flyway_schema_history entry
    cur.execute("""
        INSERT INTO flyway_schema_history
            (installed_rank, version, description, type, script, checksum,
             installed_by, installed_on, execution_time, success)
        VALUES (
            (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
            %s, %s, 'SQL', %s, %s,
            current_user, %s, 0, TRUE
        )
    """, (version, description, f"V{version}__{description.replace(' ', '_')}.sql",
          checksum, now))

    conn.commit()
    cur.close()
    conn.close()
    print(f"  [OK] V{version} applied. checksum={checksum}")


def main():
    print("=== Applying V8 to crypto DB ===")
    apply_migration(CRYPTO_DB, V8_FILE, "8", "create user trading state")

    print("\n=== Applying V9 to social DB ===")
    apply_migration(SOCIAL_DB, V9_FILE, "9", "add missing profile columns")

    print("\n=== Verifying flyway_schema_history ===")
    for label, params in [("crypto", CRYPTO_DB), ("social", SOCIAL_DB)]:
        conn = psycopg2.connect(**params)
        cur = conn.cursor()
        cur.execute("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")
        rows = cur.fetchall()
        print(f"\n  {label} DB migrations:")
        for r in rows:
            status = "✓" if r[2] else "✗"
            print(f"    {status} V{r[0]} — {r[1]}")
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()
