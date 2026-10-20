package com.ridehailing.driver.exception;

import java.util.Map;

/** T10 — 409 DRIVER_NOT_AVAILABLE (contract §1.2): reserve khi driver không AVAILABLE hoặc heartbeat stale. */
public class DriverNotAvailableException extends RuntimeException {
    private final Map<String, Object> details;

    public DriverNotAvailableException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
