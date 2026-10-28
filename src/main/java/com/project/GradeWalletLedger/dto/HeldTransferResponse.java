package com.project.GradeWalletLedger.dto;

import java.time.Instant;
import java.util.List;

public record HeldTransferResponse(
        long transferId,
        Long initiatedByUserId,
        String transferType,
        long amountPaise,
        Instant createdAt,
        List<String> firedRules) {
}