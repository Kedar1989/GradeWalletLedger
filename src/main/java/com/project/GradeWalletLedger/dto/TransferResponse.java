package com.project.GradeWalletLedger.dto;

import java.time.Instant;

public record TransferResponse(
        long id,
        String transferType,
        String status,
        long amountPaise,
                Instant createdAt) {
}