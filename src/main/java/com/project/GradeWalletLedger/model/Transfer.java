package com.project.GradeWalletLedger.model;

import java.time.Instant;

public record Transfer(
        long id,
        Long initiatedByUserId,
        TransferType transferType,
        TransferStatus status,
        long amountPaise,
        String currency,
        Long reversalOfTransferId,
        String description,
        Instant createdAt) {
}