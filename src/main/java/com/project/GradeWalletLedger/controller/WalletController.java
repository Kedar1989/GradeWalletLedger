package com.project.GradeWalletLedger.controller;

import com.project.GradeWalletLedger.dto.CreateTransferRequest;
import com.project.GradeWalletLedger.dto.CreateUserRequest;
import com.project.GradeWalletLedger.dto.CreateWalletRequest;
import com.project.GradeWalletLedger.dto.BalanceResponse;
import com.project.GradeWalletLedger.dto.HttpResult;
import com.project.GradeWalletLedger.dto.LedgerEntryResponse;
import com.project.GradeWalletLedger.dto.StatementAnswerResponse;
import com.project.GradeWalletLedger.dto.StatementQuestionRequest;
import com.project.GradeWalletLedger.service.IdempotencyService;
import com.project.GradeWalletLedger.service.StatementQaService;
import com.project.GradeWalletLedger.service.WalletService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {
    private final WalletService walletService;
    private final StatementQaService statementQaService;
    private final IdempotencyService idempotencyService;

    public WalletController(
            WalletService walletService,
            StatementQaService statementQaService,
            IdempotencyService idempotencyService) {
        this.walletService = walletService;
        this.statementQaService = statementQaService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/")
    public Map<String, String> status() {
        return Map.of("service", "Grade Wallet Ledger API", "status", "running");
    }

    @PostMapping("/api/users")
    public ResponseEntity<String> createUser(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request) {
        return response(walletService.createUser(parseKey(idempotencyKey), request));
    }

    @PostMapping("/api/users/{userId}/wallets")
    public ResponseEntity<String> createWallet(
            @PathVariable long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWalletRequest request) {
        return response(walletService.createWallet(userId, parseKey(idempotencyKey), request));
    }

    @PostMapping("/api/users/{userId}/transfers")
    public ResponseEntity<String> createTransfer(
            @PathVariable long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        return response(walletService.createTransfer(userId, parseKey(idempotencyKey), request));
    }

    @GetMapping("/api/users/{userId}/wallets/{walletId}/balance")
    public BalanceResponse getBalance(@PathVariable long userId, @PathVariable long walletId) {
        return walletService.getBalance(userId, walletId);
    }

    @GetMapping("/api/users/{userId}/wallets/{walletId}/statement")
    public List<LedgerEntryResponse> getStatement(
            @PathVariable long userId,
            @PathVariable long walletId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long offset) {
        return walletService.getStatement(userId, walletId, limit, offset);
    }

    @PostMapping("/api/users/{userId}/statement/ask")
    public ResponseEntity<String> askStatement(
            @PathVariable long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StatementQuestionRequest request) {
        HttpResult result = idempotencyService.execute(
                userId,
                parseKey(idempotencyKey),
                request,
                200,
                () -> statementQaService.answer(userId, request.question()));
        return response(result);
    }

    private UUID parseKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID");
        }
    }

    private ResponseEntity<String> response(HttpResult result) {
        return ResponseEntity.status(result.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }
}