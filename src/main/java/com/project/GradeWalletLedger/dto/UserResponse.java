package com.project.GradeWalletLedger.dto;

public record UserResponse(long id, String name, String email, String phone, String kycStatus, String accessToken) {
}