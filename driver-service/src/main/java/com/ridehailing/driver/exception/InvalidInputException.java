package com.ridehailing.driver.exception;

/** T09 — 400 INVALID_INPUT (contract §1.2): body/tọa độ/tham số không hợp lệ. */
public class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}
