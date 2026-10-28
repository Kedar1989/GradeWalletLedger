package com.project.GradeWalletLedger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.GradeWalletLedger.dto.ApiError;
import com.project.GradeWalletLedger.dto.HttpResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SecretKeySpec responseEncryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public IdempotencyService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.idempotency.encryption-key:dev-only-change-this-key}") String encryptionKey) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.responseEncryptionKey = new SecretKeySpec(sha256(encryptionKey), "AES");
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public HttpResult execute(
            Long userId,
            UUID key,
            Object request,
            int successCode,
            Supplier<Object> operation) {
        UUID requestId = UUID.randomUUID();
        return transactionTemplate.execute(status -> {
            String hash = requestHash(request);
            int inserted = jdbc.update(
                    "INSERT INTO idempotency_keys(user_id, idempotency_key, request_hash, status) "
                        + "VALUES (?, ?, ?, 'PROCESSING') ON CONFLICT DO NOTHING",
                    userId, key, hash);
            if (inserted == 0) {
                IdempotencyRow existing = find(userId, key);
                if (!existing.requestHash().equals(hash)) {
                    return jsonResult(422, new ApiError(
                            "IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used with a different request",
                            requestId.toString()));
                }
                if (!existing.status().equals("COMPLETED")) {
                    throw new WalletException("REQUEST_IN_PROGRESS", "Request with this key is still in progress", 409);
                }
                return new HttpResult(existing.responseCode(), decrypt(existing.responseBody()));
            }

            int responseCode = successCode;
            String responseBody;
            try {
                responseBody = serialize(operation.get());
            } catch (WalletException exception) {
                responseCode = exception.statusCode();
                responseBody = serialize(new ApiError(
                        exception.code(), exception.getMessage(), requestId.toString()));
            }

            jdbc.update(
                    "UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ?, "
                            + "response_body = ?, completed_at = CURRENT_TIMESTAMP "
                            + "WHERE user_id IS NOT DISTINCT FROM ? AND idempotency_key = ?",
                        responseCode, encrypt(responseBody), userId, key);
            return new HttpResult(responseCode, responseBody);
        });
    }

        private IdempotencyRow find(Long userId, UUID key) {
        String sql = userId == null
                ? "SELECT request_hash, status, response_code, response_body FROM idempotency_keys "
                + "WHERE user_id IS NULL AND idempotency_key = ?"
                : "SELECT request_hash, status, response_code, response_body FROM idempotency_keys "
                + "WHERE user_id = ? AND idempotency_key = ?";
        List<IdempotencyRow> rows = userId == null
                ? jdbc.query(sql, (row, rowNumber) -> new IdempotencyRow(
                        row.getString("request_hash"), row.getString("status"),
                row.getInt("response_code"), row.getString("response_body")), key)
                : jdbc.query(sql, (row, rowNumber) -> new IdempotencyRow(
                        row.getString("request_hash"), row.getString("status"),
                row.getInt("response_code"), row.getString("response_body")), userId, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Conflicting idempotency record was not found");
        }
        return rows.getFirst();
    }

    private String requestHash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize response", exception);
        }
    }

    private String encrypt(String value) {
        try {
            byte[] nonce = new byte[12];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, responseEncryptionKey, new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "enc:v1:" + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt idempotency response", exception);
        }
    }

    private String decrypt(String value) {
        if (!value.startsWith("enc:v1:")) {
            throw new IllegalStateException("Stored idempotency response is not encrypted");
        }
        try {
            String[] parts = value.split(":", 4);
            byte[] nonce = Base64.getDecoder().decode(parts[2]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[3]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, responseEncryptionKey, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not decrypt idempotency response", exception);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private HttpResult jsonResult(int statusCode, Object body) {
        return new HttpResult(statusCode, serialize(body));
    }

    private record IdempotencyRow(String requestHash, String status, int responseCode, String responseBody) {
    }
}