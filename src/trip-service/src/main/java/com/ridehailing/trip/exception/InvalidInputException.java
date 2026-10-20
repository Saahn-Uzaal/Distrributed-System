package com.ridehailing.trip.exception;

/** T12 — 400 INVALID_INPUT (contract §1.2): body/tọa độ/tham số/idempotency key không hợp lệ. */
public class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}
