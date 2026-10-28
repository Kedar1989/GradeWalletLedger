# PostgreSQL Isolation Lab

Run the following using two `psql` sessions connected to the same development database. The lab uses isolated temporary tables and does not change wallet data. Outputs below are expected PostgreSQL observations; rerun them locally to record the exact server output for your environment.

## Setup

Run once in either session:

```sql
CREATE TABLE isolation_lab_balance (id integer PRIMARY KEY, balance integer NOT NULL);
INSERT INTO isolation_lab_balance VALUES (1, 100);

CREATE TABLE isolation_lab_doctors (id integer PRIMARY KEY, on_call boolean NOT NULL);
INSERT INTO isolation_lab_doctors VALUES (1, true), (2, true);

CREATE TABLE isolation_lab_parent (id integer PRIMARY KEY, value integer NOT NULL);
CREATE TABLE isolation_lab_child (id integer PRIMARY KEY, parent_id integer REFERENCES isolation_lab_parent(id));
INSERT INTO isolation_lab_parent VALUES (1, 10);
INSERT INTO isolation_lab_child VALUES (1, 1);
```

## Read Committed

Session A:

```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM isolation_lab_balance WHERE id = 1;
-- 100
```

Session B:

```sql
BEGIN;
UPDATE isolation_lab_balance SET balance = 90 WHERE id = 1;
COMMIT;
```

Session A:

```sql
SELECT balance FROM isolation_lab_balance WHERE id = 1;
-- 90: each statement sees a fresh committed snapshot.
COMMIT;
```

Reset the row to 100 before repeating another lab.

## Repeatable Read

Session A:

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM isolation_lab_balance WHERE id = 1;
-- 100
```

Session B updates the value to 90 and commits, as above. Session A:

```sql
SELECT balance FROM isolation_lab_balance WHERE id = 1;
-- 100: A continues to read its transaction snapshot.
COMMIT;
```

## Serializable Write Skew

Reset both doctors to `on_call = true`. In each session, begin Serializable, count the on-call doctors, then turn off a different doctor.

Session A:

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT count(*) FROM isolation_lab_doctors WHERE on_call;
-- 2
UPDATE isolation_lab_doctors SET on_call = false WHERE id = 1;
```

Session B, before A commits:

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT count(*) FROM isolation_lab_doctors WHERE on_call;
-- 2
UPDATE isolation_lab_doctors SET on_call = false WHERE id = 2;
COMMIT;
```

Commit Session A. PostgreSQL aborts one transaction with SQLSTATE `40001` (`could not serialize access due to read/write dependencies among transactions`). Retry the aborted whole transaction.

## Repeatable Read Join

Session A:

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT p.value, c.parent_id
FROM isolation_lab_parent p JOIN isolation_lab_child c ON c.parent_id = p.id
WHERE p.id = 1;
-- 10 | 1
```

Session B updates `isolation_lab_parent.value` to 20 and commits. Session A repeats the join and still sees `10 | 1`. A transaction snapshot covers joined tables too.

## Wallet Transfer Choice

Wallet transfers use Read Committed plus `SELECT ... FOR UPDATE` on all participating account rows ordered by account ID. The row locks serialize balance checks and ledger writes for overlapping wallets while allowing unrelated wallets to proceed. The transfer transaction remains short and explicit; the service re-reads the locked balance before posting. Serializable would require retrying serialization failures and reduce throughput without replacing the row locks.