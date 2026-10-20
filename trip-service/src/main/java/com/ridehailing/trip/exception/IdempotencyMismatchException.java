package com.ridehailing.trip.exception;

import java.util.Map;

/** T12 — 409 IDEMPOTENCY_MISMATCH (contract §1.2): cùng Idempotency-Key, payload khác. */
public class IdempotencyMismatchException extends RuntimeException {
    private final Map<String, Object> details;

    public IdempotencyMismatchException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
