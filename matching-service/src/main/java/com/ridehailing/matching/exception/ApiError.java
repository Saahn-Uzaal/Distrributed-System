package com.ridehailing.matching.exception;

/** T07 — lỗi JSON thống nhất {code, message, correlationId, details} (contract §1.1). */
public record ApiError(String code, String message, String correlationId, Object details) {
}
