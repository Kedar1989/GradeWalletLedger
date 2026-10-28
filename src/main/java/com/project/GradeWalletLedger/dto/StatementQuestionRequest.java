package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StatementQuestionRequest(@NotBlank @Size(max = 1000) String question) {
}