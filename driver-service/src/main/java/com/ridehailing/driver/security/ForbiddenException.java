package com.ridehailing.driver.security;

/** T07 — 403 FORBIDDEN (role không đủ cho thao tác, contract §1.2). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
