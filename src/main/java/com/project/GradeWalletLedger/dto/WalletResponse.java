package com.project.GradeWalletLedger.dto;

public record WalletResponse(long id, long ownerUserId, String name, String currency) {
}