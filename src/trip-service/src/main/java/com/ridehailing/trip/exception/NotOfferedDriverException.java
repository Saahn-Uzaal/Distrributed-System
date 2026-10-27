package com.ridehailing.trip.exception;

import java.util.Map;

/** T18/T19 — 409 NOT_OFFERED_DRIVER (contract §1.2): accept/reject bởi driver không phải người được offer cho trip+attempt đó. */
public class NotOfferedDriverException extends RuntimeException {
    private final Map<String, Object> details;

    public NotOfferedDriverException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
