package com.project.GradeWalletLedger.dto;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Long runId,
        String status,
        int problemCount,
        Instant startedAt,
        Instant finishedAt,
        List<ReconciliationProblem> problems) {
}