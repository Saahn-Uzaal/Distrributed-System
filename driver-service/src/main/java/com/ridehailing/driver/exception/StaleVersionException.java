package com.ridehailing.driver.exception;

import java.util.Map;

/** T10 — 409 STALE_VERSION (contract §1.2): commandVersion <= last_command_version đã lưu. */
public class StaleVersionException extends RuntimeException {
    private final Map<String, Object> details;

    public StaleVersionException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
