# Grade Wallet Ledger

Spring Boot and PostgreSQL backend for a paise-based digital wallet. The initial migration establishes wallet and system accounts, transfers, immutable double-entry ledger rows, idempotency storage, audit records, reconciliation tables, and risk configuration.

## Local Setup and Run

### Prerequisites

- Java 21+
- Docker Desktop or Docker Engine with Compose
- Git

### 1) Clone and start the app

From the project root:

```sh
git clone <your-repo-url>
cd GradeWalletLedger

docker compose up --build
```

On Windows PowerShell, the same commands are:

```powershell
git clone <your-repo-url>
cd GradeWalletLedger
docker compose up --build
```

This starts:
- PostgreSQL on port `5432`
- the Spring Boot API on port `8080`
- the background worker for reconciliation and risk checks

The API is available at:

```text
http://localhost:8080
```

Compose credentials are intended for local development only. If you run the app without Docker, set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` explicitly in the environment or in the application config.

### 2) Run the app without Docker

If you already have PostgreSQL running locally, start the backend with Gradle:

```sh
./gradlew bootRun
```

On Windows:

```powershell
./gradlew.bat bootRun
```

### 3) Run tests locally

Run the project test suite with:

```sh
./gradlew test --no-daemon
```

Windows:

```powershell
./gradlew.bat test --no-daemon
```

This includes unit tests and PostgreSQL-backed integration tests when Docker is available. If Docker is not running, the DB integration tests are skipped.

### 4) Stop the services

```sh
docker compose down
```

To remove volumes as well:

```sh
docker compose down -v
```

## Current API

- `POST /api/users` creates a user and returns an opaque bearer token.
- `POST /api/users/{userId}/wallets` creates an INR wallet.
- `POST /api/users/{userId}/transfers` accepts `TOP_UP`, `PEER_TRANSFER`, `WITHDRAWAL`, `FEE`, and `REVERSAL` requests.
- `GET /api/users/{userId}/wallets/{walletId}/balance` reads the cached paise balance.
- `GET /api/users/{userId}/wallets/{walletId}/statement?limit=50&offset=0` returns ledger rows newest first.
- `POST /api/users/{userId}/statement/ask` answers supported statement questions and returns supporting transfer IDs.

Every POST requires an `Idempotency-Key` UUID. Transfer responses are stored and replayed for the same key and body; using that key with a different body returns 422. The transfer transaction explicitly uses PostgreSQL Read Committed and locks all affected accounts in ascending ID order before checking funds and inserting ledger rows.

Send the returned token as `Authorization: Bearer <token>` on every `/api/users/{userId}/...` request. Tokens are random 256-bit values; only their SHA-256 hashes are stored in `user_api_tokens`. A token is bound to the `userId` in the path, so it cannot read or modify another user's wallets. Idempotency response bodies are AES-GCM encrypted at rest; configure a stable secret with `IDEMPOTENCY_ENCRYPTION_KEY` so retries remain replayable after restarts.

## Admin and Background Jobs

- `POST /api/admin/reconciliation/run` runs all consistency checks under Repeatable Read and `FOR UPDATE NOWAIT`.
- `GET /api/admin/transfers/held` lists held transfers and the rules that flagged them.
- `GET /api/admin/risk-rules` and `PUT /api/admin/risk-rules/{ruleName}` inspect and change thresholds.
- `POST /api/admin/transfers/{transferId}/release` releases a held transfer after review.
- Admin routes require `X-Admin-Token`; configure `ADMIN_API_TOKEN` and an existing actor `ADMIN_USER_ID` outside local development. The client cannot choose the audit actor.
- The nightly reconciliation and risk explanation job runs at 02:00 UTC by default. Override with `RECONCILIATION_CRON`.
- Set `STATEMENT_LLM_ENABLED=true` and `STATEMENT_LLM_API_KEY` to enable the OpenAI-compatible provider. The default mock works offline and in tests.

## Seed Data

After `docker compose up --build`, load 1,000 users, 2,000 wallets, and 100,000 balanced top-ups into a fresh database:

```sh
docker compose exec -T postgres psql -U gradewallet -d gradewallet -f /seed/seed.sql
```

The seed script is for a newly migrated database and is intentionally not idempotent.

## Design

See [docs/schema.md](docs/schema.md) for the noun inventory, cardinality decisions, ER diagram, and integrity trade-offs. See [docs/isolation.md](docs/isolation.md) for the two-session isolation lab, [docs/statement-questions.md](docs/statement-questions.md) for ten sample questions, and [docs/query-plans.md](docs/query-plans.md) for expected plan trade-offs. Money is stored as integer paise. Wallet balances are cached in `accounts.balance_paise`, maintained by the ledger insert trigger, and checked against ledger-derived balances during reconciliation.

## Assumptions

- User, wallet, and account IDs are generated `BIGINT`s; email and phone are unique contact fields, not identifiers.
- User and system accounts share `accounts`; only `USER_WALLET` rows have owners and a nonnegative balance constraint. System accounts may go negative.
- Account balances are cached for fast reads and updated only by the ledger insert trigger. The ledger is authoritative and reconciliation reports drift without repairing it.
- A held transfer stores an immutable posting plan but creates no ledger entries until release. A completed transfer must have at least two entries whose debit and credit totals each equal the transfer amount.
- A fee is an explicit `FEE` transfer from a user wallet to the fee-revenue account. The brief does not define a fee schedule.
- Reversals copy the original entries with directions reversed, point to the original transfer, and are limited to one reversal per original.
- Fraud thresholds default to a 500,000-paise single-transfer hold, a flag after 10 transfers in an hour, and a hold when a rolling 24-hour amount would exceed 1,000,000 paise. Admins can update the stored threshold JSON.
- Statement Q&A dates are inclusive calendar dates in UTC. The LLM only extracts a supported intent; all values and transfer IDs come from caller-scoped SQL.
- The static admin token is a local protection mechanism, not production authentication. Replace it with managed identity and role-based authorization before deployment.

PostgreSQL integration tests use Testcontainers and require Docker. The test suite includes the 20-round 100-request stampede, idempotency retries, reversals, risk holds/releases, prompt-injection isolation, and reconciliation corruption checks.