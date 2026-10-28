package com.project.GradeWalletLedger.dto;

import java.util.List;

public record StatementAnswerResponse(
        String type,
        long value,
        String unit,
        List<Long> transferIds,
        String answer) {
}