package com.project.GradeWalletLedger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GradeWalletLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.run(GradeWalletLedgerApplication.class, args);
	}
}
