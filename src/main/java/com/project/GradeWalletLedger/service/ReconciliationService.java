package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.GradeWalletLedger.dto.ReconciliationProblem;
import com.project.GradeWalletLedger.dto.ReconciliationReport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ReconciliationService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReconciliationReport run() {
        try {
            return transactionTemplate.execute(status -> runInSnapshot());
        } catch (CannotAcquireLockException exception) {
            return new ReconciliationReport(null, "ALREADY_RUNNING", 0, Instant.now(), Instant.now(), List.of());
        } catch (RuntimeException exception) {
            Instant finished = Instant.now();
            Long runId = jdbc.queryForObject(
                    "INSERT INTO reconciliation_runs(started_at, finished_at, status, problem_count, details) "
                            + "VALUES (?, ?, 'FAILED', 0, ?::jsonb) RETURNING id",
                    Long.class,
                    Timestamp.from(finished),
                    Timestamp.from(finished),
                    json("{\"error\":\"reconciliation failed\"}"));
            return new ReconciliationReport(runId, "FAILED", 0, finished, finished, List.of());
        }
    }

    private ReconciliationReport runInSnapshot() {
        jdbc.queryForObject(
                "SELECT id FROM job_locks WHERE job_name = 'nightly_reconciliation' FOR UPDATE NOWAIT",
                Long.class);
        Instant started = Instant.now();
        Long runId = jdbc.queryForObject(
                "INSERT INTO reconciliation_runs(started_at, status) VALUES (?, 'RUNNING') RETURNING id",
                Long.class,
                Timestamp.from(started));
        List<ReconciliationProblem> problems = new ArrayList<>();

        checkGlobalBalance(problems);
        checkTransferBalances(problems);
        checkStoredBalances(problems);
        checkWalletOverdrafts(problems);
        checkMissingEntries(problems);
        checkOrphanEntries(problems);

        Instant finished = Instant.now();
        String reportStatus = problems.isEmpty() ? "SUCCEEDED" : "MISMATCHES";
        jdbc.batchUpdate(
                "INSERT INTO reconciliation_problems(run_id, check_name, entity_id, expected_value, actual_value) "
                        + "VALUES (?, ?, ?, ?, ?)",
                problems,
                250,
                (statement, problem) -> {
                    statement.setLong(1, runId);
                    statement.setString(2, problem.checkName());
                    if (problem.entityId() == null) {
                        statement.setNull(3, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(3, problem.entityId());
                    }
                    statement.setString(4, problem.expectedValue());
                    statement.setString(5, problem.actualValue());
                });
        jdbc.update(
                "UPDATE reconciliation_runs SET finished_at = ?, status = ?, problem_count = ?, details = ?::jsonb "
                        + "WHERE id = ?",
                Timestamp.from(finished), reportStatus, problems.size(),
                json("{\"checks\":6,\"problem_count\":" + problems.size() + "}"), runId);
        return new ReconciliationReport(runId, reportStatus, problems.size(), started, finished, problems);
    }

    private void checkGlobalBalance(List<ReconciliationProblem> problems) {
        List<LedgerTotals> rows = jdbc.query(
                "WITH transfer_deltas AS (SELECT t.id, "
                        + "COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'DEBIT'), 0) "
                        + "- COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'CREDIT'), 0) AS delta "
                        + "FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id = t.id "
                        + "WHERE t.status = 'COMPLETED' GROUP BY t.id) "
                        + "SELECT COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0)::text AS debits, "
                        + "COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)::text AS credits, "
                        + "COALESCE((SELECT SUM(delta) FROM transfer_deltas), 0)::text AS explained_delta "
                        + "FROM ledger_entries",
                (row, rowNumber) -> new LedgerTotals(
                        row.getString("debits"), row.getString("credits"), row.getString("explained_delta")));
        LedgerTotals totals = rows.getFirst();
        java.math.BigInteger bookDelta = new java.math.BigInteger(totals.debits())
                .subtract(new java.math.BigInteger(totals.credits()));
        if (!bookDelta.equals(new java.math.BigInteger(totals.explainedDelta()))) {
            problems.add(new ReconciliationProblem(
                    "GLOBAL_DEBIT_CREDIT_TOTAL", null, totals.debits(), totals.credits()));
        }
    }

    private void checkTransferBalances(List<ReconciliationProblem> problems) {
        problems.addAll(jdbc.query(
                "SELECT t.id, t.amount_paise::text AS expected, "
                        + "COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'DEBIT'), 0)::text AS debits, "
                        + "COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'CREDIT'), 0)::text AS credits, "
                        + "COUNT(le.id) AS entry_count "
                        + "FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id = t.id "
                        + "WHERE t.status = 'COMPLETED' GROUP BY t.id "
                        + "HAVING COUNT(le.id) < 2 "
                        + "OR COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'DEBIT'), 0) <> t.amount_paise "
                        + "OR COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction = 'CREDIT'), 0) <> t.amount_paise",
                (row, rowNumber) -> new ReconciliationProblem(
                        "TRANSFER_BALANCE", row.getLong("id"), row.getString("expected"),
                        "debits=" + row.getString("debits") + ",credits=" + row.getString("credits")
                                + ",entries=" + row.getLong("entry_count"))));
    }

    private void checkStoredBalances(List<ReconciliationProblem> problems) {
        problems.addAll(jdbc.query(
                "SELECT a.id, a.balance_paise::text AS stored, "
                        + "COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount_paise "
                        + "ELSE -le.amount_paise END), 0)::text AS computed "
                        + "FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id "
                        + "GROUP BY a.id, a.balance_paise "
                        + "HAVING a.balance_paise <> COALESCE(SUM(CASE le.direction WHEN 'CREDIT' "
                        + "THEN le.amount_paise ELSE -le.amount_paise END), 0) "
                        + "AND NOT EXISTS (SELECT 1 FROM transfer_postings tp WHERE tp.account_id = a.id "
                        + "AND tp.transfer_id IN (SELECT t.id FROM transfers t "
                        + "LEFT JOIN ledger_entries broken_entry ON broken_entry.transfer_id = t.id "
                        + "WHERE t.status = 'COMPLETED' GROUP BY t.id, t.amount_paise "
                        + "HAVING COUNT(broken_entry.id) < 2 "
                        + "OR COALESCE(SUM(broken_entry.amount_paise) FILTER (WHERE broken_entry.direction = 'DEBIT'), 0) <> t.amount_paise "
                        + "OR COALESCE(SUM(broken_entry.amount_paise) FILTER (WHERE broken_entry.direction = 'CREDIT'), 0) <> t.amount_paise))",
                (row, rowNumber) -> new ReconciliationProblem(
                        "STORED_BALANCE", row.getLong("id"), row.getString("computed"), row.getString("stored"))));
    }

    private void checkWalletOverdrafts(List<ReconciliationProblem> problems) {
        problems.addAll(jdbc.query(
                "SELECT a.id, COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount_paise "
                        + "ELSE -le.amount_paise END), 0)::text AS computed "
                        + "FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id "
                        + "WHERE a.account_type = 'USER_WALLET' GROUP BY a.id "
                        + "HAVING COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount_paise "
                        + "ELSE -le.amount_paise END), 0) < 0",
                (row, rowNumber) -> new ReconciliationProblem(
                        "WALLET_OVERDRAFT", row.getLong("id"), "0 or more", row.getString("computed"))));
    }

    private void checkMissingEntries(List<ReconciliationProblem> problems) {
        problems.addAll(jdbc.query(
                "SELECT t.id, COUNT(le.id) AS entry_count FROM transfers t "
                        + "LEFT JOIN ledger_entries le ON le.transfer_id = t.id "
                        + "WHERE t.status = 'COMPLETED' GROUP BY t.id HAVING COUNT(le.id) = 0",
                (row, rowNumber) -> new ReconciliationProblem(
                        "TRANSFER_WITHOUT_ENTRIES", row.getLong("id"), "at least 2", "0")));
    }

    private void checkOrphanEntries(List<ReconciliationProblem> problems) {
        problems.addAll(jdbc.query(
                "SELECT le.id, le.transfer_id FROM ledger_entries le "
                        + "LEFT JOIN transfers t ON t.id = le.transfer_id WHERE t.id IS NULL",
                (row, rowNumber) -> new ReconciliationProblem(
                        "ORPHAN_LEDGER_ENTRY", row.getLong("id"), "existing transfer", row.getString("transfer_id"))));
    }

    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize reconciliation details", exception);
        }
    }

        private record LedgerTotals(String debits, String credits, String explainedDelta) {
    }
}