package com.project.GradeWalletLedger.model;

import java.time.Instant;

public record Account(
	long id,
	Long ownerUserId,
	AccountType accountType,
	String name,
	String currency,
	long balancePaise,
	Instant createdAt) {
}
