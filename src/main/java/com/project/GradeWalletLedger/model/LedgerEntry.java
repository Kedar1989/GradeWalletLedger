package com.project.GradeWalletLedger.model;

import java.time.Instant;

public record LedgerEntry(
        long id,
        long transferId,
        long accountId,
        LedgerDirection direction,
        long amountPaise,
        Instant createdAt) {
}