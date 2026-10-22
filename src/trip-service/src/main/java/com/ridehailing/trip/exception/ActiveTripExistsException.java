package com.ridehailing.trip.exception;

import java.util.Map;

/** T12-6 (N28) — 409 ACTIVE_TRIP_EXISTS: passenger đã có trip chưa terminal, không tạo thêm. */
public class ActiveTripExistsException extends RuntimeException {
    private final Map<String, Object> details;

    public ActiveTripExistsException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
