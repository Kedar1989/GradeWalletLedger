package com.project.GradeWalletLedger.controller;

import com.project.GradeWalletLedger.dto.HttpResult;
import com.project.GradeWalletLedger.dto.HeldTransferResponse;
import com.project.GradeWalletLedger.dto.ReleaseTransferRequest;
import com.project.GradeWalletLedger.dto.RiskRuleRequest;
import com.project.GradeWalletLedger.service.IdempotencyService;
import com.project.GradeWalletLedger.service.ReconciliationService;
import com.project.GradeWalletLedger.service.RiskRuleService;
import com.project.GradeWalletLedger.service.WalletService;
import com.project.GradeWalletLedger.service.WalletException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {
    private final ReconciliationService reconciliationService;
    private final RiskRuleService riskRuleService;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final byte[] adminToken;
    private final long adminUserId;

    public AdminController(
            ReconciliationService reconciliationService,
            RiskRuleService riskRuleService,
            WalletService walletService,
            IdempotencyService idempotencyService,
            @Value("${app.admin.token:dev-admin-token}") String adminToken,
            @Value("${app.admin.user-id:1}") long adminUserId) {
        if (adminToken.isBlank()) {
            throw new IllegalStateException("ADMIN_API_TOKEN must not be blank");
        }
        this.reconciliationService = reconciliationService;
        this.riskRuleService = riskRuleService;
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
        this.adminUserId = adminUserId;
    }

    @PostMapping("/api/admin/reconciliation/run")
    public ResponseEntity<String> runReconciliation(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireAdmin(token);
        HttpResult result = idempotencyService.execute(
            adminUserId, parseKey(key), new AdminAction("RECONCILIATION"), 200, reconciliationService::run);
        return response(result);
    }

    @GetMapping("/api/admin/risk-rules")
    public Object listRiskRules(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        return riskRuleService.listRules();
    }

    @GetMapping("/api/admin/transfers/held")
    public List<HeldTransferResponse> listHeldTransfers(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
        return walletService.listHeldTransfers(100);
    }

    @PutMapping("/api/admin/risk-rules/{ruleName}")
    public ResponseEntity<String> saveRiskRule(
            @PathVariable String ruleName,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody RiskRuleRequest request) {
        requireAdmin(token);
        HttpResult result = idempotencyService.execute(
                adminUserId,
                parseKey(key),
                new RiskRuleCommand(ruleName, request),
                200,
                () -> riskRuleService.saveRule(ruleName, request));
        return response(result);
    }

    @PostMapping("/api/admin/transfers/{transferId}/release")
    public ResponseEntity<String> releaseTransfer(
            @PathVariable long transferId,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReleaseTransferRequest request) {
        requireAdmin(token);
        HttpResult result = walletService.releaseHeldTransfer(
            transferId, adminUserId, parseKey(key), request.reason());
        return response(result);
    }

    private void requireAdmin(String token) {
        if (token == null || !MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new WalletException("ADMIN_UNAUTHORIZED", "Admin token is invalid", 401);
        }
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

    private record AdminAction(String action) {
    }

    private record RiskRuleCommand(String ruleName, RiskRuleRequest request) {
    }
}