package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.project.GradeWalletLedger.dto.RiskRuleRequest;
import com.project.GradeWalletLedger.dto.RiskRuleResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RiskRuleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RiskRuleService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<RiskRuleResponse> listRules() {
        return jdbc.query(
                "SELECT rule_name, enabled, action, thresholds FROM risk_rules ORDER BY rule_name",
                (row, rowNumber) -> new RiskRuleResponse(
                        row.getString("rule_name"), row.getBoolean("enabled"), row.getString("action"),
                        thresholdMap(row.getString("thresholds"))));
    }

    public RiskRuleResponse saveRule(String ruleName, RiskRuleRequest request) {
                if (!Set.of("single_transfer_amount", "hourly_velocity", "daily_amount").contains(ruleName)) {
                        throw new WalletException("UNKNOWN_RISK_RULE", "Unknown risk rule", 404);
                }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!action.equals("HOLD") && !action.equals("FLAG")) {
            throw new WalletException("INVALID_RISK_ACTION", "Risk action must be HOLD or FLAG", 400);
        }
                if (request.thresholds() == null || request.thresholds().isEmpty()) {
                        throw new WalletException("INVALID_RISK_THRESHOLDS", "Risk thresholds must be a non-empty object", 400);
        }
                String thresholdName = switch (ruleName) {
                        case "single_transfer_amount" -> "minimum_amount_paise";
                        case "hourly_velocity" -> "maximum_count";
                        default -> "maximum_amount_paise";
                };
                Long thresholdValue = request.thresholds().get(thresholdName);
                if (thresholdValue == null || thresholdValue < 1) {
                        throw new WalletException("INVALID_RISK_THRESHOLDS", "Risk threshold must be a positive integer", 400);
                }
        jdbc.update(
                "INSERT INTO risk_rules(rule_name, enabled, action, thresholds, updated_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (rule_name) DO UPDATE SET enabled = EXCLUDED.enabled, "
                        + "action = EXCLUDED.action, thresholds = EXCLUDED.thresholds, updated_at = CURRENT_TIMESTAMP",
                ruleName, request.enabled(), action, serializeThresholds(request.thresholds()));
        return jdbc.queryForObject(
                "SELECT rule_name, enabled, action, thresholds FROM risk_rules WHERE rule_name = ?",
                (row, rowNumber) -> new RiskRuleResponse(
                        row.getString("rule_name"), row.getBoolean("enabled"), row.getString("action"),
                        thresholdMap(row.getString("thresholds"))),
                ruleName);
    }

    public List<RiskDecision> evaluate(long userId, long amountPaise, Instant at) {
                List<RiskRule> rules = jdbc.query(
                "SELECT rule_name, action, thresholds FROM risk_rules WHERE enabled ORDER BY id",
                                (row, rowNumber) -> new RiskRule(
                                                row.getString("rule_name"), row.getString("action"),
                                                parseThresholds(row.getString("thresholds"))));
                return rules.stream().map(rule -> {
                        boolean matched = switch (rule.name()) {
                                case "single_transfer_amount" -> amountPaise >= rule.thresholds().path("minimum_amount_paise").asLong(Long.MAX_VALUE);
                                case "hourly_velocity" -> hourlyVelocityExceeded(
                                                userId, rule.thresholds().path("maximum_count").asLong(Long.MAX_VALUE), at);
                                case "daily_amount" -> dailyAmountExceeded(
                                                userId, amountPaise,
                                                rule.thresholds().path("maximum_amount_paise").asLong(Long.MAX_VALUE), at);
                                default -> false;
                        };
                            return new RiskDecision(rule.name(), rule.action(), matched, rule.thresholds());
                }).toList();
    }

    private boolean hourlyVelocityExceeded(long userId, long maximumCount, Instant at) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE initiated_by_user_id = ? "
                        + "AND status IN ('COMPLETED', 'HELD') AND created_at >= ? AND created_at < ?",
                Long.class,
                userId,
                Timestamp.from(at.minusSeconds(3600)),
                Timestamp.from(at));
        return count != null && count >= maximumCount;
    }

    private boolean dailyAmountExceeded(long userId, long amountPaise, long maximumAmount, Instant at) {
        Long existingAmount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_paise), 0) FROM transfers WHERE initiated_by_user_id = ? "
                        + "AND status IN ('COMPLETED', 'HELD') AND created_at >= ? AND created_at < ?",
                Long.class,
                userId,
                Timestamp.from(at.minusSeconds(86400)),
                Timestamp.from(at));
        return existingAmount != null && amountPaise > maximumAmount - existingAmount;
    }

    private JsonNode parseThresholds(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored risk thresholds are invalid", exception);
        }
    }

        private Map<String, Object> thresholdMap(String value) {
                return objectMapper.convertValue(parseThresholds(value), new TypeReference<>() { });
        }

        private String serializeThresholds(Map<String, Long> thresholds) {
                try {
                        return objectMapper.writeValueAsString(thresholds);
                } catch (JsonProcessingException exception) {
                        throw new IllegalArgumentException("Risk thresholds are invalid", exception);
                }
        }

    private record RiskRule(String name, String action, JsonNode thresholds) {
    }

        public record RiskDecision(String ruleName, String action, boolean matched, JsonNode thresholds) {
    }
}