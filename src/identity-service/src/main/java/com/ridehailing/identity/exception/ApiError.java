package com.ridehailing.identity.exception;

/** Định dạng lỗi thống nhất (contract api-contracts.md §1.1): {code, message, correlationId, details}. */
public record ApiError(String code, String message, String correlationId, Object details) {
}
