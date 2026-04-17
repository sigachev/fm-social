# Stage 1.1 — virtual_balance Home Investigation

**Generated:** 2026-04-16  
**Investigator:** Claude Code Phase A

---

## Findings

### Portfolio Entity Location

`F:\Projects\finmates-crypto\src\main\java\com\finmates\model\Portfolio.java`  
Table: `portfolios`

### Portfolio Entity Fields

| Field | Column | Type | Notes |
|-------|--------|------|-------|
| `id` | `id` | BIGSERIAL PK | Auto-generated |
| `userId` | `user_id` | BIGINT NOT NULL | FK to finmates-main users |
| `username` | `username` | VARCHAR(100) | Denormalized (added V3) |
| `name` | `name` | VARCHAR(100) NOT NULL | Portfolio name |
| `description` | `description` | VARCHAR(500) | Nullable |
| `type` | `type` | VARCHAR(20) NOT NULL | VIRTUAL / MANUAL / SYNCED |
| `provider` | `provider` | VARCHAR(30) | HYPERLIQUID / COINBASE / BINANCE / KRAKEN |
| `providerAccountId` | `provider_account_id` | VARCHAR(255) | Nullable |
| `initialBalance` | `initial_balance` | NUMERIC(20,2) DEFAULT 10000 | Starting cash |
| `cashBalance` | `cash_balance` | NUMERIC(20,2) DEFAULT 10000 | **Current remaining cash** |
| `isDefault` | `is_default` | BOOLEAN DEFAULT FALSE | One per user |
| `isPublic` | `is_public` | BOOLEAN DEFAULT FALSE | — |
| `syncEnabled` | `sync_enabled` | BOOLEAN DEFAULT FALSE | — |
| `lastSyncedAt` | `last_synced_at` | TIMESTAMPTZ | Nullable |
| `createdAt` | `created_at` | TIMESTAMPTZ | Auto-set @PrePersist |
| `updatedAt` | `updated_at` | TIMESTAMPTZ | Auto-set @PreUpdate |

**Annotations:** `@Entity @Table(name = "portfolios")` — `@Getter @Setter @NoArgsConstructor @Builder @AllArgsConstructor`

### User-to-Portfolio Relationship

- **NO unique constraint on `user_id`** — multiple portfolios per user are **explicitly supported**
- **One default per user:** partial unique index `idx_portfolios_one_default ON portfolios(user_id) WHERE is_default = TRUE`
- **Max portfolios:** enforced at service layer (`count >= 10` check in `PortfolioService.createPortfolio()`)
- A user may have VIRTUAL, MANUAL, and SYNCED portfolios simultaneously

### Existing `virtual_balance` References in finmates-crypto

**One reference found:**  
`F:\Projects\finmates-crypto\src\main\resources\db\migration\migrate_from_main.sql`

This is a standalone (non-Flyway-versioned) reference migration script that documents the original cross-DB data move:
```sql
-- maps finmates-main.users.virtual_balance → portfolios.cash_balance
COALESCE(u.virtual_balance, 10000.00) → portfolios.cash_balance
```

**No Java code, services, controllers, or Flyway migrations reference `virtual_balance` or `virtualBalance`.** The concept already exists semantically in the crypto DB as `portfolios.cash_balance` — the amount of cash remaining in a portfolio after trades.

### Flyway Migration Inventory

| Version | File | Purpose |
|---------|------|---------|
| V1 | `V1__multi_portfolio_schema.sql` | Created portfolios, portfolio_trades, portfolio_positions, synced_positions, portfolio_snapshots |
| V2 | `V2__seed_default_portfolios.sql` | Seeded initial default portfolios |
| V3 | `V3__add_username_to_portfolio.sql` | Added username column for leaderboard |
| V4 | *(missing — gap in sequence)* | Not present |
| V5 | `V5__seed_test_holdings.sql` | Seeded test holdings |
| V6 | `V6__asset_comment_schema.sql` | Asset comment schema (migrating to fm-social) |
| V7 | `V7__asset_comment_reaction.sql` | Asset comment reactions (migrating to fm-social) |

**Current max Flyway version: V7.** Next migration will be V8.

---

## Key Architectural Distinction

`virtual_balance` in `finmates-main.users` is a **user-level** field — one value per user, representing their total virtual trading account balance. It starts at $10,000 and changes with trades.

`portfolios.cash_balance` in finmates-crypto is a **portfolio-level** field — each portfolio (VIRTUAL, MANUAL, SYNCED) has its own cash balance. A user can have multiple VIRTUAL portfolios, each with independent `cash_balance`.

These are semantically different:
- `virtual_balance` = total virtual account funding (user scope)
- `cash_balance` = remaining cash in one specific portfolio (portfolio scope)

---

## Live Data Check (finmates-main.users)

Users with non-default virtual_balance (evidence of actual trading):

| user_id | username | virtual_balance |
|---------|----------|----------------|
| 1 | sigachev | 7,872.09 (started at 10,000) |
| 9 | smoketest | 8,974.585 (started at 10,000) |

All other 15 users: 10,000.00 (untouched defaults)

---

## Recommendation: Option (b) — Create `user_trading_state` table

**Rationale:**

1. `virtual_balance` is **user-scoped**, not portfolio-scoped. Adding it to the `portfolios` table would misplace it — which portfolio would "own" the user's virtual balance? The default? What if the default is deleted?
2. The `portfolios.cash_balance` already tracks per-portfolio remaining cash — that's a different concept. Adding `virtual_balance` to portfolios creates confusion between two cash concepts on the same row.
3. A dedicated `user_trading_state` table (PK: user_id) cleanly expresses "one virtual balance per user" with a clear schema guarantee (PRIMARY KEY = uniqueness).
4. Phase B will need to update `finmates-main` to write `virtual_balance` changes to finmates-crypto. A named table (`user_trading_state`) is an unambiguous target for those writes.
5. The existing `migrate_from_main.sql` already acknowledged this mapping happened once — the new table makes it canonical and first-class.

---

## SQL for Option (b)

**Migration file:** `F:\Projects\finmates-crypto\src\main\resources\db\migration\V8__create_user_trading_state.sql`

```sql
-- V8: Create user_trading_state table for user-level virtual balance
-- Replaces virtual_balance column in finmates-main.users (Phase B cleanup)
CREATE TABLE user_trading_state (
    user_id         BIGINT PRIMARY KEY,
    virtual_balance NUMERIC(20,8) NOT NULL DEFAULT 10000,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE user_trading_state IS
    'User-level virtual trading account state. One row per user. '
    'virtual_balance is the total virtual USD available across all portfolios. '
    'Replaces virtual_balance column previously in finmates-main.users.';
```

**Java entity:** `UserTradingState.java` at `com.finmates.model`

```java
@Entity
@Table(name = "user_trading_state")
@Getter @Setter @NoArgsConstructor @Builder @AllArgsConstructor
public class UserTradingState {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "virtual_balance", nullable = false, precision = 20, scale = 8)
    private BigDecimal virtualBalance = BigDecimal.valueOf(10000);

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

**Repository:** `UserTradingStateRepository.java` extending `JpaRepository<UserTradingState, Long>`

---

## Data to Migrate (Stage 5)

From `finmates-main.users`:
```sql
-- Only 2 users have non-default balances:
-- user_id=1 → 7872.09000000
-- user_id=9 → 8974.58500000
-- All others → 10000.00 (will get DEFAULT when row is created)
```

Export file: `F:\Projects\fm-social\phase-a-export\virtual-balance-export.csv`
