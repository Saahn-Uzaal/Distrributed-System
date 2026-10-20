package com.ridehailing.driver.exception;

import java.util.Map;

/** T09 — 409 STATE_CONFLICT (contract §1.2): chuyển trạng thái sai; details = state hiện tại + tripId liên quan. */
public class StateConflictException extends RuntimeException {
    private final Map<String, Object> details;

    public StateConflictException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
