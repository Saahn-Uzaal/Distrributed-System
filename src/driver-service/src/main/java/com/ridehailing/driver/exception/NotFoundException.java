package com.ridehailing.driver.exception;

/** T09 — 404 NOT_FOUND (contract §1.2): resource không tồn tại (vd user chưa tạo profile driver). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
