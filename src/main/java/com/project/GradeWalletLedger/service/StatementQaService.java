package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.GradeWalletLedger.dto.StatementAnswerResponse;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatementQaService {
    private static final String UNSUPPORTED_MESSAGE =
            "I can answer totals sent to or received from a person, the largest transfer, "
                    + "or the number of transfers in a date range.";

    private final JdbcTemplate jdbc;
    private final StatementQuestionInterpreter interpreter;

    public StatementQaService(JdbcTemplate jdbc, StatementQuestionInterpreter interpreter) {
        this.jdbc = jdbc;
        this.interpreter = interpreter;
    }

    public StatementAnswerResponse answer(long userId, String question) {
        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        if (userCount == null || userCount == 0) {
            throw new WalletException("USER_NOT_FOUND", "User was not found", 404);
        }
        JsonNode intent = interpreter.interpret(question);
        String type = intent.path("type").asText("").toUpperCase(Locale.ROOT);
        if (!List.of("TOTAL_SENT_TO_PERSON", "TOTAL_RECEIVED", "LARGEST_TRANSFER", "TRANSFER_COUNT")
                .contains(type)) {
            throw new WalletException("UNSUPPORTED_STATEMENT_QUESTION", UNSUPPORTED_MESSAGE, 422);
        }
        String counterparty = null;
        if (type.equals("TOTAL_SENT_TO_PERSON")
            || type.equals("TOTAL_RECEIVED") && intent.hasNonNull("counterparty")) {
            counterparty = intent.path("counterparty").asText("").trim();
            if (counterparty.isBlank() || counterparty.length() > 80) {
                throw new WalletException("UNSUPPORTED_STATEMENT_QUESTION", UNSUPPORTED_MESSAGE, 422);
            }
        }

        LocalDate from = parseDate(intent.path("from").asText(null), LocalDate.of(1970, 1, 1));
        LocalDate to = parseDate(intent.path("to").asText(null), LocalDate.now());
        if (from.isAfter(to)) {
            throw new WalletException("INVALID_DATE_RANGE", "The start date must not be after the end date", 400);
        }

        List<TransferAmount> transfers = queryTransfers(userId, type, counterparty, from, to);
        long value;
        List<Long> transferIds;
        String unit;
        String answer;
        if (type.equals("TRANSFER_COUNT")) {
            value = transfers.size();
            transferIds = transfers.stream().map(TransferAmount::id).toList();
            unit = "TRANSFERS";
            answer = "You had " + value + " transfer(s) in that date range.";
        } else if (type.equals("LARGEST_TRANSFER")) {
            value = transfers.stream().mapToLong(TransferAmount::amountPaise).max().orElse(0);
            transferIds = transfers.stream().filter(row -> row.amountPaise() == value).map(TransferAmount::id).toList();
            unit = "PAISE";
            answer = "Your largest transfer was " + value + " paise.";
        } else {
            value = transfers.stream().mapToLong(TransferAmount::amountPaise).sum();
            transferIds = transfers.stream().map(TransferAmount::id).toList();
            unit = "PAISE";
            if (type.equals("TOTAL_SENT_TO_PERSON")) {
                answer = "You sent " + value + " paise to " + counterparty
                        + " across " + transferIds.size() + " transfer(s).";
            } else if (counterparty == null) {
                answer = "You received " + value + " paise across " + transferIds.size() + " transfer(s).";
            } else {
                answer = "You received " + value + " paise from " + counterparty
                        + " across " + transferIds.size() + " transfer(s).";
            }
        }
        return new StatementAnswerResponse(type, value, unit, transferIds, answer);
    }

    private List<TransferAmount> queryTransfers(
            long userId,
            String type,
            String counterparty,
            LocalDate from,
            LocalDate to) {
        Timestamp fromTimestamp = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp toTimestamp = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        String query;
        Object[] args;
        if (type.equals("TOTAL_SENT_TO_PERSON")
                || type.equals("TOTAL_RECEIVED") && counterparty != null) {
            boolean sent = type.equals("TOTAL_SENT_TO_PERSON");
            String ownDirection = sent ? "DEBIT" : "CREDIT";
            String otherDirection = sent ? "CREDIT" : "DEBIT";
            query = "SELECT DISTINCT t.id, t.amount_paise FROM transfers t "
                    + "JOIN ledger_entries own_entry ON own_entry.transfer_id = t.id AND own_entry.direction = ? "
                    + "JOIN accounts own_account ON own_account.id = own_entry.account_id "
                    + "JOIN ledger_entries other_entry ON other_entry.transfer_id = t.id AND other_entry.direction = ? "
                    + "JOIN accounts other_account ON other_account.id = other_entry.account_id "
                    + "JOIN users other_user ON other_user.id = other_account.owner_user_id "
                    + "WHERE own_account.owner_user_id = ? AND LOWER(other_user.name) = LOWER(?) "
                    + "AND t.status = 'COMPLETED' AND t.created_at >= ? AND t.created_at < ? "
                    + "AND t.transfer_type IN ('PEER_TRANSFER', 'REVERSAL') ORDER BY t.id";
            args = new Object[] {ownDirection, otherDirection, userId, counterparty, fromTimestamp, toTimestamp};
        } else {
            String directionFilter = type.equals("TOTAL_RECEIVED") ? "AND le.direction = 'CREDIT' " : "";
            query = "SELECT DISTINCT t.id, t.amount_paise FROM transfers t "
                    + "JOIN ledger_entries le ON le.transfer_id = t.id "
                    + "JOIN accounts a ON a.id = le.account_id "
                        + "WHERE a.owner_user_id = ? AND t.status = 'COMPLETED' " + directionFilter
                    + "AND t.created_at >= ? AND t.created_at < ? "
                    + "AND t.transfer_type IN ('TOP_UP', 'PEER_TRANSFER', 'WITHDRAWAL', 'FEE', 'REVERSAL') "
                    + "ORDER BY t.id";
            args = new Object[] {userId, fromTimestamp, toTimestamp};
        }
        return jdbc.query(query, (row, rowNumber) -> new TransferAmount(
                row.getLong("id"), row.getLong("amount_paise")), args);
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new WalletException("INVALID_DATE_RANGE", "Dates must use YYYY-MM-DD", 400);
        }
    }

    private record TransferAmount(long id, long amountPaise) {
    }
}