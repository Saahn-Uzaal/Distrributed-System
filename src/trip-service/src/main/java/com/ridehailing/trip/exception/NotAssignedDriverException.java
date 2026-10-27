package com.ridehailing.trip.exception;

import java.util.Map;

/** T22 — 409 NOT_ASSIGNED_DRIVER (contract §1.2/§3.3): start/complete bởi driver không phải người được assign cho trip đó. */
public class NotAssignedDriverException extends RuntimeException {
    private final Map<String, Object> details;

    public NotAssignedDriverException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
