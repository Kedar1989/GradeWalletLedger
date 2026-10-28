package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RiskFlagExplainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RiskFlagExplainer.class);
    private static final Set<String> ALLOWED_LABELS = Set.of("likely_ok", "review", "likely_fraud");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StatementQuestionInterpreter interpreter;

    public RiskFlagExplainer(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            StatementQuestionInterpreter interpreter) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.interpreter = interpreter;
    }

    public int explainTodaysFlags() {
        List<FlagRow> flags = jdbc.query(
                "SELECT rf.id, rf.transfer_id, rf.rule_name "
                        + "FROM risk_flags rf JOIN transfers t ON t.id = rf.transfer_id "
                    + "WHERE rf.explanation IS NULL ORDER BY rf.id",
                (row, rowNumber) -> new FlagRow(
                    row.getLong("id"), row.getLong("transfer_id"), row.getString("rule_name")));
        int explained = 0;
        for (FlagRow flag : flags) {
            try {
                JsonNode context = contextFor(flag);
                StatementQuestionInterpreter.RiskExplanation result =
                        interpreter.explainRisk(flag.ruleName(), context);
                String label = result.label().toLowerCase(java.util.Locale.ROOT);
                if (!ALLOWED_LABELS.contains(label) || result.explanation().isBlank()
                        || result.explanation().length() > 500) {
                    throw new IllegalArgumentException("Risk explanation response failed validation");
                }
                jdbc.update(
                        "UPDATE risk_flags SET explanation = ?, label = ? WHERE id = ?",
                        result.explanation(), label.toUpperCase(java.util.Locale.ROOT), flag.id());
                explained++;
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not explain risk flag {}", flag.id());
            }
        }
        return explained;
    }

    private JsonNode contextFor(FlagRow flag) {
        Map<String, Object> rule = jdbc.queryForMap(
            "SELECT rule_name, action, rule_snapshot::text AS thresholds FROM risk_flags WHERE id = ?", flag.id());
        List<Map<String, Object>> recentTransfers = jdbc.queryForList(
                "SELECT t.id, t.transfer_type, t.status, t.amount_paise, t.created_at, t.description "
                + "FROM transfers t JOIN transfer_postings recent ON recent.transfer_id = t.id "
                + "WHERE recent.account_id IN (SELECT target.account_id "
                + "FROM transfer_postings target JOIN accounts wallet ON wallet.id = target.account_id "
                + "WHERE target.transfer_id = ? AND wallet.account_type = 'USER_WALLET') "
                + "GROUP BY t.id ORDER BY t.created_at DESC, t.id DESC LIMIT 10",
            flag.transferId());
        return objectMapper.valueToTree(Map.of(
                "triggered_transfer_id", flag.transferId(),
                "triggered_rule", rule,
                "last_10_wallet_transfers", recentTransfers));
    }

        private record FlagRow(long id, long transferId, String ruleName) {
    }
}