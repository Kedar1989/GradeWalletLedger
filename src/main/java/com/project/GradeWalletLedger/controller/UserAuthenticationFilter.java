package com.project.GradeWalletLedger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.GradeWalletLedger.dto.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class UserAuthenticationFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UserAuthenticationFilter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/users") && request.getMethod().equals("POST")
                || path.startsWith("/api/admin/")
                || !path.startsWith("/api/users/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Long pathUserId = parseUserId(request.getRequestURI());
        if (pathUserId == null) {
            writeError(response, 401, "AUTHENTICATION_REQUIRED", "A valid bearer token is required");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            writeError(response, 401, "AUTHENTICATION_REQUIRED", "A valid bearer token is required");
            return;
        }
        Long tokenUserId = findTokenOwner(authorization.substring(7));
        if (tokenUserId == null) {
            writeError(response, 401, "AUTHENTICATION_REQUIRED", "A valid bearer token is required");
            return;
        }
        if (!tokenUserId.equals(pathUserId)) {
            writeError(response, 403, "USER_MISMATCH", "Token cannot access another user's resources");
            return;
        }
        request.setAttribute("authenticatedUserId", tokenUserId);
        filterChain.doFilter(request, response);
    }

    private Long findTokenOwner(String token) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        List<Long> users = jdbc.query(
                "SELECT user_id FROM user_api_tokens WHERE token_hash = ? AND revoked_at IS NULL",
                (row, rowNumber) -> row.getLong("user_id"),
                hash);
        return users.isEmpty() ? null : users.getFirst();
    }

    private Long parseUserId(String path) {
        String[] parts = path.split("/");
        if (parts.length < 4 || !parts[1].equals("api") || !parts[2].equals("users")) {
            return null;
        }
        try {
            return Long.parseLong(parts[3]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        objectMapper.writeValue(
                response.getWriter(), new ApiError(code, message, UUID.randomUUID().toString()));
    }
}