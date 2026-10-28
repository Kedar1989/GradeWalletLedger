package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank @Size(max = 120) String name) {
}