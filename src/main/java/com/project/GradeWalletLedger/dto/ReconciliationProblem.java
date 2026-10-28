package com.project.GradeWalletLedger.dto;

public record ReconciliationProblem(
        String checkName,
        Long entityId,
        String expectedValue,
        String actualValue) {
}