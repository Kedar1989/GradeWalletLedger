package com.project.GradeWalletLedger.controller;

import com.project.GradeWalletLedger.dto.ApiError;
import com.project.GradeWalletLedger.service.WalletException;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ApiError> walletError(WalletException exception) {
        return ResponseEntity.status(exception.statusCode()).body(error(
                exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalError(Exception exception) {
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(error(
                "INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private ApiError error(String code, String message) {
        return new ApiError(code, message, UUID.randomUUID().toString());
    }
}