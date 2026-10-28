package com.project.GradeWalletLedger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiError(
        String code,
        String message,
        @JsonProperty("request_id") String requestId) {
}