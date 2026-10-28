package com.project.GradeWalletLedger.service;

import com.project.GradeWalletLedger.dto.BalanceResponse;
import com.project.GradeWalletLedger.dto.CreateTransferRequest;
import com.project.GradeWalletLedger.dto.CreateUserRequest;
import com.project.GradeWalletLedger.dto.CreateWalletRequest;
import com.project.GradeWalletLedger.dto.HttpResult;
import com.project.GradeWalletLedger.dto.HeldTransferResponse;
import com.project.GradeWalletLedger.dto.LedgerEntryResponse;
import com.project.GradeWalletLedger.dto.TransferResponse;
import com.project.GradeWalletLedger.dto.UserResponse;
import com.project.GradeWalletLedger.dto.WalletResponse;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WalletService {
    private static final String USER_WALLET = "USER_WALLET";

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotencyService;
    private final RiskRuleService riskRuleService;
    private final SecureRandom secureRandom = new SecureRandom();

    public WalletService(JdbcTemplate jdbc, IdempotencyService idempotencyService, RiskRuleService riskRuleService) {
        this.jdbc = jdbc;
        this.idempotencyService = idempotencyService;
        this.riskRuleService = riskRuleService;
    }

    public HttpResult createUser(UUID key, CreateUserRequest request) {
        String accessToken = newAccessToken();
        return idempotencyService.execute(null, key, request, 201, () -> {
            List<UserResponse> created = jdbc.query(
                    "INSERT INTO users(name, email, phone) VALUES (?, ?, ?) "
                            + "ON CONFLICT DO NOTHING "
                            + "RETURNING id, name, email, phone, kyc_status",
                    (row, rowNumber) -> new UserResponse(
                            row.getLong("id"), row.getString("name"), row.getString("email"),
                                row.getString("phone"), row.getString("kyc_status"), accessToken),
                    request.name(), request.email(), request.phone());
            if (created.isEmpty()) {
                throw new WalletException("USER_CONTACT_EXISTS", "Email or phone is already registered", 409);
            }
                        jdbc.update(
                            "INSERT INTO user_api_tokens(user_id, token_hash) VALUES (?, ?)",
                            created.getFirst().id(), tokenHash(accessToken));
            return created.getFirst();
        });
    }

    public HttpResult createWallet(long userId, UUID key, CreateWalletRequest request) {
        return idempotencyService.execute(userId, key, request, 201, () -> {
            requireUser(userId);
            return jdbc.queryForObject(
                    "INSERT INTO accounts(owner_user_id, account_type, account_name) "
                            + "VALUES (?, 'USER_WALLET', ?) RETURNING id, owner_user_id, account_name, currency",
                    (row, rowNumber) -> new WalletResponse(
                            row.getLong("id"), row.getLong("owner_user_id"),
                            row.getString("account_name"), row.getString("currency")),
                    userId, request.name());
        });
    }

    public HttpResult createTransfer(long userId, UUID key, CreateTransferRequest request) {
        return idempotencyService.execute(userId, key, request, 201, () -> createTransfer(userId, request));
    }

    public HttpResult releaseHeldTransfer(long transferId, long adminUserId, UUID key, String reason) {
        ReleaseRequest request = new ReleaseRequest(transferId, adminUserId, reason);
        return idempotencyService.execute(
            adminUserId, key, request, 200, () -> releaseHeldTransfer(transferId, adminUserId, reason));
    }

    public BalanceResponse getBalance(long userId, long walletId) {
        List<Long> balances = jdbc.query(
                "SELECT balance_paise FROM accounts "
                        + "WHERE id = ? AND owner_user_id = ? AND account_type = 'USER_WALLET'",
                (row, rowNumber) -> row.getLong("balance_paise"),
                walletId, userId);
        if (balances.isEmpty()) {
            throw new WalletException("WALLET_NOT_FOUND", "Wallet was not found", 404);
        }
        return new BalanceResponse(walletId, balances.getFirst(), "INR");
    }

    public List<LedgerEntryResponse> getStatement(long userId, long walletId, int limit, long offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new WalletException("INVALID_PAGINATION", "Limit must be 1-100 and offset cannot be negative", 400);
        }
        requireOwnedAccount(walletId, userId);
        return jdbc.query(
                "SELECT le.id, le.transfer_id, le.direction, le.amount_paise, "
                        + "t.transfer_type, le.created_at "
                        + "FROM ledger_entries le JOIN transfers t ON t.id = le.transfer_id "
                        + "WHERE le.account_id = ? "
                        + "ORDER BY le.created_at DESC, le.id DESC LIMIT ? OFFSET ?",
                (row, rowNumber) -> new LedgerEntryResponse(
                        row.getLong("id"), row.getLong("transfer_id"), row.getString("direction"),
                        row.getLong("amount_paise"), row.getString("transfer_type"),
                        row.getTimestamp("created_at").toInstant()),
                walletId, limit, offset);
    }

            public List<HeldTransferResponse> listHeldTransfers(int limit) {
            if (limit < 1 || limit > 100) {
                throw new WalletException("INVALID_PAGINATION", "Limit must be 1-100", 400);
            }
            return jdbc.query(
                "SELECT t.id, t.initiated_by_user_id, t.transfer_type, t.amount_paise, t.created_at, "
                    + "COALESCE(array_agg(rf.rule_name ORDER BY rf.rule_name) "
                    + "FILTER (WHERE rf.rule_name IS NOT NULL), ARRAY[]::varchar[]) AS fired_rules "
                    + "FROM transfers t LEFT JOIN risk_flags rf ON rf.transfer_id = t.id "
                    + "WHERE t.status = 'HELD' GROUP BY t.id ORDER BY t.created_at, t.id LIMIT ?",
                (row, rowNumber) -> new HeldTransferResponse(
                    row.getLong("id"), row.getObject("initiated_by_user_id", Long.class),
                    row.getString("transfer_type"), row.getLong("amount_paise"),
                    row.getTimestamp("created_at").toInstant(),
                    List.of((String[]) row.getArray("fired_rules").getArray())),
                limit);
            }

    private TransferResponse createTransfer(long userId, CreateTransferRequest request) {
        requireUser(userId);
        String transferType = normalizeTransferType(request.transferType());
        if (request.amountPaise() <= 0) {
            throw new WalletException("INVALID_AMOUNT", "Amount must be positive paise", 400);
        }

        Instant now = Instant.now();
        List<Posting> postings;
        Long reversalOfTransferId = null;
        switch (transferType) {
            case "TOP_UP" -> postings = topUpPlan(userId, request);
            case "WITHDRAWAL" -> postings = withdrawalPlan(userId, request);
            case "PEER_TRANSFER" -> postings = peerTransferPlan(userId, request);
            case "FEE" -> postings = feePlan(userId, request);
            case "REVERSAL" -> {
                if (request.reversalOfTransferId() == null) {
                    throw new WalletException("REVERSAL_TARGET_REQUIRED", "A reversal must reference its original transfer", 400);
                }
                OriginalTransfer original = lockOriginalTransfer(request.reversalOfTransferId());
                if (!original.status().equals("COMPLETED") || original.transferType().equals("REVERSAL")) {
                    throw new WalletException("TRANSFER_NOT_REVERSIBLE", "Only completed non-reversal transfers can be reversed", 422);
                }
                requireWalletShape(request.sourceWalletId() == null && request.destinationWalletId() == null);
                if (request.amountPaise() != original.amountPaise()) {
                    throw new WalletException("REVERSAL_AMOUNT_MISMATCH", "Reversal amount must match the original transfer", 422);
                }
                postings = reversalPlan(userId, original);
                reversalOfTransferId = original.id();
            }
            default -> throw new WalletException("UNSUPPORTED_TRANSFER_TYPE", "Unsupported transfer type", 400);
        }

        Map<Long, AccountRow> accounts = lockAccounts(postings.stream().map(Posting::accountId).toList());
        validateTransferOwnership(transferType, userId, request, accounts);
        validatePostings(postings, accounts, request.amountPaise());
        enforceFunds(postings, accounts);
        List<RiskRuleService.RiskDecision> decisions = riskRuleService.evaluate(userId, request.amountPaise(), now);
        boolean held = decisions.stream().anyMatch(decision -> decision.matched() && decision.action().equals("HOLD"));
        String status = held ? "HELD" : "COMPLETED";

        setAuditActor(userId, "USER", null);
        TransferRow transfer = insertTransfer(
                userId, transferType, status, request.amountPaise(), request.description(), reversalOfTransferId);
        savePostings(transfer.id(), postings);
        saveRiskFlags(transfer.id(), decisions);
        if (!held) {
            postLedgerEntries(transfer.id(), postings);
            if (reversalOfTransferId != null) {
                jdbc.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", reversalOfTransferId);
            }
        }
        return new TransferResponse(transfer.id(), transferType, status, request.amountPaise(), transfer.createdAt());
    }

    private TransferResponse releaseHeldTransfer(long transferId, long adminUserId, String reason) {
        requireUser(adminUserId);
        TransferRow transfer = jdbc.queryForObject(
                "SELECT id, initiated_by_user_id, transfer_type, status, amount_paise, reversal_of_transfer_id "
                        + "FROM transfers WHERE id = ? FOR UPDATE",
                (row, rowNumber) -> new TransferRow(
                        row.getLong("id"), row.getObject("initiated_by_user_id", Long.class),
                        row.getString("transfer_type"), row.getString("status"), row.getLong("amount_paise"),
                        row.getObject("reversal_of_transfer_id", Long.class), null),
                transferId);
        if (!transfer.status().equals("HELD")) {
            throw new WalletException("TRANSFER_NOT_HELD", "Transfer is not held for review", 409);
        }
        List<Posting> postings = jdbc.query(
                "SELECT account_id, direction, amount_paise FROM transfer_postings "
                        + "WHERE transfer_id = ? ORDER BY sequence_number",
                (row, rowNumber) -> new Posting(
                        row.getLong("account_id"), row.getString("direction"), row.getLong("amount_paise")),
                transferId);
        if (postings.size() < 2) {
            throw new WalletException("INVALID_HELD_TRANSFER", "Held transfer has no complete posting plan", 500);
        }
        Map<Long, AccountRow> accounts = lockAccounts(postings.stream().map(Posting::accountId).toList());
        validatePostings(postings, accounts, transfer.amountPaise());
        enforceFunds(postings, accounts);
        setAuditActor(adminUserId, "ADMIN", reason);
        jdbc.update("UPDATE transfers SET status = 'COMPLETED' WHERE id = ?", transferId);
        postLedgerEntries(transferId, postings);
        if (transfer.reversalOfTransferId() != null) {
            jdbc.update("UPDATE transfers SET status = 'REVERSED' WHERE id = ?", transfer.reversalOfTransferId());
        }
        return new TransferResponse(
                transfer.id(), transfer.transferType(), "COMPLETED", transfer.amountPaise(), transfer.createdAt());
    }

    private List<Posting> topUpPlan(long userId, CreateTransferRequest request) {
        requireWalletShape(request.sourceWalletId() == null && request.destinationWalletId() != null);
        return List.of(
                new Posting(findSystemAccount("CASH_IN"), "DEBIT", request.amountPaise()),
                new Posting(request.destinationWalletId(), "CREDIT", request.amountPaise()));
    }

    private List<Posting> withdrawalPlan(long userId, CreateTransferRequest request) {
        requireWalletShape(request.sourceWalletId() != null && request.destinationWalletId() == null);
        return List.of(
                new Posting(request.sourceWalletId(), "DEBIT", request.amountPaise()),
                new Posting(findSystemAccount("CASH_OUT"), "CREDIT", request.amountPaise()));
    }

    private List<Posting> peerTransferPlan(long userId, CreateTransferRequest request) {
        requireWalletShape(request.sourceWalletId() != null && request.destinationWalletId() != null
                && !request.sourceWalletId().equals(request.destinationWalletId()));
        return List.of(
                new Posting(request.sourceWalletId(), "DEBIT", request.amountPaise()),
                new Posting(request.destinationWalletId(), "CREDIT", request.amountPaise()));
    }

    private List<Posting> feePlan(long userId, CreateTransferRequest request) {
        requireWalletShape(request.sourceWalletId() != null && request.destinationWalletId() == null);
        return List.of(
                new Posting(request.sourceWalletId(), "DEBIT", request.amountPaise()),
                new Posting(findSystemAccount("FEE_REVENUE"), "CREDIT", request.amountPaise()));
    }

    private List<Posting> reversalPlan(long userId, OriginalTransfer original) {
        List<Posting> originalPostings = jdbc.query(
                "SELECT account_id, direction, amount_paise FROM ledger_entries "
                        + "WHERE transfer_id = ? ORDER BY id",
                (row, rowNumber) -> new Posting(
                        row.getLong("account_id"), row.getString("direction"), row.getLong("amount_paise")),
                original.id());
        if (originalPostings.size() < 2) {
            throw new WalletException("TRANSFER_NOT_REVERSIBLE", "Original transfer has no complete ledger entries", 422);
        }
        TreeSet<Long> ownerIds = new TreeSet<>(originalPostings.stream().map(Posting::accountId).toList());
        List<Long> callerAccounts = jdbc.query(
                "SELECT id FROM accounts WHERE owner_user_id = ? AND id IN ("
                        + String.join(",", ownerIds.stream().map(ignored -> "?").toList()) + ")",
                (row, rowNumber) -> row.getLong("id"),
                prepend(userId, ownerIds.toArray()));
        if (callerAccounts.isEmpty()) {
            throw new WalletException("TRANSFER_NOT_FOUND", "Original transfer was not found", 404);
        }
        return originalPostings.stream()
                .map(posting -> new Posting(
                        posting.accountId(), posting.direction().equals("DEBIT") ? "CREDIT" : "DEBIT",
                        posting.amountPaise()))
                .toList();
    }

    private OriginalTransfer lockOriginalTransfer(long transferId) {
        List<OriginalTransfer> transfers = jdbc.query(
                "SELECT id, transfer_type, status, amount_paise FROM transfers WHERE id = ? FOR UPDATE",
                (row, rowNumber) -> new OriginalTransfer(
                    row.getLong("id"), row.getString("transfer_type"), row.getString("status"),
                    row.getLong("amount_paise")),
                transferId);
        if (transfers.isEmpty()) {
            throw new WalletException("TRANSFER_NOT_FOUND", "Original transfer was not found", 404);
        }
        Integer priorReversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE reversal_of_transfer_id = ?", Integer.class, transferId);
        if (priorReversals != null && priorReversals > 0) {
            throw new WalletException("TRANSFER_ALREADY_REVERSED", "Transfer already has a reversal", 409);
        }
        return transfers.getFirst();
    }

    private void validateTransferOwnership(
            String transferType,
            long userId,
            CreateTransferRequest request,
            Map<Long, AccountRow> accounts) {
        switch (transferType) {
            case "TOP_UP" -> requireOwnedWallet(accounts.get(request.destinationWalletId()), userId);
            case "WITHDRAWAL", "FEE" -> requireOwnedWallet(accounts.get(request.sourceWalletId()), userId);
            case "PEER_TRANSFER" -> {
                requireOwnedWallet(accounts.get(request.sourceWalletId()), userId);
                AccountRow recipient = accounts.get(request.destinationWalletId());
                if (recipient == null || !recipient.accountType().equals(USER_WALLET)) {
                    throw new WalletException("WALLET_NOT_FOUND", "Recipient wallet was not found", 404);
                }
            }
            case "REVERSAL" -> {
                boolean participant = accounts.values().stream()
                        .anyMatch(account -> account.accountType().equals(USER_WALLET)
                                && account.ownerUserId() != null && account.ownerUserId() == userId);
                if (!participant) {
                    throw new WalletException("TRANSFER_NOT_FOUND", "Original transfer was not found", 404);
                }
            }
            default -> throw new WalletException("UNSUPPORTED_TRANSFER_TYPE", "Unsupported transfer type", 400);
        }
    }

    private void requireOwnedWallet(AccountRow account, long userId) {
        if (account == null || !account.accountType().equals(USER_WALLET)
                || account.ownerUserId() == null || account.ownerUserId() != userId) {
            throw new WalletException("WALLET_NOT_FOUND", "Wallet was not found", 404);
        }
    }

    private void validatePostings(List<Posting> postings, Map<Long, AccountRow> accounts, long expectedAmount) {
        if (postings.size() < 2) {
            throw new WalletException("INVALID_TRANSFER_POSTINGS", "A transfer requires at least two postings", 400);
        }
        long debits = 0;
        long credits = 0;
        for (Posting posting : postings) {
            AccountRow account = accounts.get(posting.accountId());
            if (account == null) {
                throw new WalletException("WALLET_NOT_FOUND", "Wallet was not found", 404);
            }
            if (account.accountType().equals(USER_WALLET) && account.ownerUserId() == null) {
                throw new WalletException("INVALID_ACCOUNT", "Wallet owner is missing", 409);
            }
            if (posting.direction().equals("DEBIT")) {
                debits = Math.addExact(debits, posting.amountPaise());
            } else {
                credits = Math.addExact(credits, posting.amountPaise());
            }
        }
        if (debits != expectedAmount || credits != expectedAmount) {
            throw new WalletException("INVALID_TRANSFER_POSTINGS", "Posting totals must equal the transfer amount", 400);
        }
    }

    private void enforceFunds(List<Posting> postings, Map<Long, AccountRow> accounts) {
        Map<Long, Long> walletDebits = new HashMap<>();
        for (Posting posting : postings) {
            AccountRow account = accounts.get(posting.accountId());
            if (posting.direction().equals("DEBIT") && account.accountType().equals(USER_WALLET)) {
                walletDebits.merge(account.id(), posting.amountPaise(), Math::addExact);
            }
        }
        for (Map.Entry<Long, Long> debit : walletDebits.entrySet()) {
            if (accounts.get(debit.getKey()).balancePaise() < debit.getValue()) {
                throw new WalletException("INSUFFICIENT_FUNDS", "Wallet has insufficient funds", 422);
            }
        }
    }

    private TransferRow insertTransfer(
            long userId,
            String transferType,
            String status,
            long amountPaise,
            String description,
            Long reversalOfTransferId) {
        return jdbc.queryForObject(
                "INSERT INTO transfers(initiated_by_user_id, transfer_type, status, amount_paise, "
                        + "description, reversal_of_transfer_id) VALUES (?, ?, ?, ?, ?, ?) "
                        + "RETURNING id, created_at",
                (row, rowNumber) -> new TransferRow(
                        row.getLong("id"), userId, transferType, status, amountPaise,
                        reversalOfTransferId, row.getTimestamp("created_at").toInstant()),
                userId, transferType, status, amountPaise, description, reversalOfTransferId);
    }

    private void savePostings(long transferId, List<Posting> postings) {
        int sequence = 1;
        for (Posting posting : postings) {
            jdbc.update(
                    "INSERT INTO transfer_postings(transfer_id, account_id, direction, amount_paise, sequence_number) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    transferId, posting.accountId(), posting.direction(), posting.amountPaise(), sequence++);
        }
    }

    private void saveRiskFlags(long transferId, List<RiskRuleService.RiskDecision> decisions) {
        for (RiskRuleService.RiskDecision decision : decisions) {
            if (decision.matched()) {
                jdbc.update(
                    "INSERT INTO risk_flags(transfer_id, rule_name, label, rule_snapshot) "
                        + "VALUES (?, ?, 'REVIEW', ?::jsonb) "
                                + "ON CONFLICT (transfer_id, rule_name) DO NOTHING",
                    transferId, decision.ruleName(), decision.thresholds().toString());
            }
        }
    }

    private void postLedgerEntries(long transferId, List<Posting> postings) {
        for (Posting posting : postings) {
            jdbc.update(
                    "INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise) "
                            + "VALUES (?, ?, ?, ?)",
                    transferId, posting.accountId(), posting.direction(), posting.amountPaise());
        }
    }

    private Map<Long, AccountRow> lockAccounts(List<Long> accountIds) {
        TreeSet<Long> sortedIds = new TreeSet<>(accountIds);
        String placeholders = String.join(", ", sortedIds.stream().map(ignored -> "?").toList());
        List<AccountRow> rows = jdbc.query(
                "SELECT id, owner_user_id, account_type, balance_paise FROM accounts "
                        + "WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                (row, rowNumber) -> new AccountRow(
                        row.getLong("id"), row.getObject("owner_user_id", Long.class),
                        row.getString("account_type"), row.getLong("balance_paise")),
                sortedIds.toArray());
        Map<Long, AccountRow> accounts = new HashMap<>();
        rows.forEach(account -> accounts.put(account.id(), account));
        return accounts;
    }

    private long findSystemAccount(String accountType) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM accounts WHERE account_type = ? AND owner_user_id IS NULL",
                (row, rowNumber) -> row.getLong("id"), accountType);
        if (ids.isEmpty()) {
            throw new IllegalStateException("Required system account is missing: " + accountType);
        }
        return ids.getFirst();
    }

    private void requireUser(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        if (count == null || count == 0) {
            throw new WalletException("USER_NOT_FOUND", "User was not found", 404);
        }
    }

    private void requireOwnedAccount(long walletId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts "
                        + "WHERE id = ? AND owner_user_id = ? AND account_type = 'USER_WALLET'",
                Integer.class,
                walletId,
                userId);
        if (count == null || count == 0) {
            throw new WalletException("WALLET_NOT_FOUND", "Wallet was not found", 404);
        }
    }

    private void setAuditActor(long userId, String label, String reason) {
        jdbc.queryForObject("SELECT set_config('app.actor_user_id', ?, TRUE)", String.class, Long.toString(userId));
        jdbc.queryForObject("SELECT set_config('app.actor_label', ?, TRUE)", String.class, label);
        jdbc.queryForObject("SELECT set_config('app.status_change_reason', ?, TRUE)", String.class,
                reason == null ? "" : reason);
    }

    private void requireWalletShape(boolean valid) {
        if (!valid) {
            throw new WalletException("INVALID_TRANSFER_ACCOUNTS", "Wallet account fields do not match transfer type", 400);
        }
    }

    private String normalizeTransferType(String transferType) {
        if (transferType == null) {
            throw new WalletException("INVALID_TRANSFER_TYPE", "Transfer type is required", 400);
        }
        return transferType.trim().toUpperCase(Locale.ROOT);
    }

    private String newAccessToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return HexFormat.of().formatHex(tokenBytes);
    }

    private String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Object[] prepend(long first, Object[] rest) {
        Object[] values = new Object[rest.length + 1];
        values[0] = first;
        System.arraycopy(rest, 0, values, 1, rest.length);
        return values;
    }

    private record Posting(long accountId, String direction, long amountPaise) {
    }

    private record AccountRow(long id, Long ownerUserId, String accountType, long balancePaise) {
    }

    private record OriginalTransfer(long id, String transferType, String status, long amountPaise) {
    }

    private record TransferRow(
            long id,
            Long initiatedByUserId,
            String transferType,
            String status,
            long amountPaise,
            Long reversalOfTransferId,
            Instant createdAt) {
    }

    private record ReleaseRequest(long transferId, long adminUserId, String reason) {
    }
}