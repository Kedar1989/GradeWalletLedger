package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record RiskRuleRequest(
        boolean enabled,
        @NotBlank String action,
        @NotNull Map<String, Long> thresholds) {
}