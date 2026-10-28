package com.project.GradeWalletLedger.dto;

public record BalanceResponse(long walletId, long balancePaise, String currency) {
}