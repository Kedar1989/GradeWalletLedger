package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTransferRequest(
        String transferType,
        Long sourceWalletId,
        Long destinationWalletId,
        @Positive long amountPaise,
                @Size(max = 500) String description,
                Long reversalOfTransferId) {

        public CreateTransferRequest(
                        String transferType,
                        Long sourceWalletId,
                        Long destinationWalletId,
                        long amountPaise,
                        String description) {
                this(transferType, sourceWalletId, destinationWalletId, amountPaise, description, null);
        }
}