package com.project.GradeWalletLedger.dto;

import java.time.Instant;

public record LedgerEntryResponse(
        long id,
        long transferId,
        String direction,
        long amountPaise,
        String transferType,
        Instant createdAt) {
}