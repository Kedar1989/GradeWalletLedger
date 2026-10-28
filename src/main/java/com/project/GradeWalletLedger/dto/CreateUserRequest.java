package com.project.GradeWalletLedger.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 32) String phone) {
}