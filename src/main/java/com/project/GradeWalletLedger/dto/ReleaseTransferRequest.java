package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReleaseTransferRequest(
        @NotBlank @Size(max = 500) String reason) {
}