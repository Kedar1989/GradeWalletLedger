# Query Plan Notes

Run the following after loading at least one million ledger entries. These are expected query shapes and trade-offs, not measured `EXPLAIN ANALYZE` output; the actual plans depend on PostgreSQL statistics, hardware, and data distribution and must be captured in the environment where the benchmark is run.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, transfer_id, direction, amount_paise, created_at
FROM ledger_entries
WHERE account_id = 1
ORDER BY created_at DESC, id DESC
LIMIT 50 OFFSET 100000;
```

`ix_ledger_account_statement` supports the account filter and order. The deep offset still makes PostgreSQL visit and discard roughly 100,000 qualifying index entries before returning 50 rows, so latency grows with the offset. Cursor pagination over `(created_at, id)` avoids that discarded prefix and should be benchmarked before replacing the required offset contract.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0),
       COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)
FROM ledger_entries;
```

The global books check necessarily reads all ledger entries; this is acceptable in the nightly snapshot job. The per-transfer balance check also aggregates all entries, using `ix_ledger_transfer` for grouping/join access where the planner estimates it helps. Stored-balance and overdraft checks need ledger-derived sums by account and can also scan all entries; nightly reconciliation favors a complete, consistent check over a smaller write-time index burden.

The transfer-without-entry and orphan-entry checks use `ix_ledger_transfer` to probe entries by transfer. The live statement query uses `ix_ledger_account_statement`. Fraud velocity and rolling amount checks filter by user and time and use `ix_transfers_initiator_created`.

To inspect all six checks on a seeded database, run `EXPLAIN (ANALYZE, BUFFERS)` around the corresponding SQL in `ReconciliationService`. Do not use the test corruption fixture as a performance dataset.