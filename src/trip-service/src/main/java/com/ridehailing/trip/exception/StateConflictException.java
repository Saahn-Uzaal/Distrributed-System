package com.ridehailing.trip.exception;

import java.util.Map;

/** T18–T20 — 409 STATE_CONFLICT (contract §1.2): chuyển trạng thái sai; details chứa state hiện tại. */
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
