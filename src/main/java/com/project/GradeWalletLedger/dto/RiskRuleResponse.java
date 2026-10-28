package com.project.GradeWalletLedger.dto;

import java.util.Map;

public record RiskRuleResponse(String ruleName, boolean enabled, String action, Map<String, Object> thresholds) {
}