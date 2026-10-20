package com.ridehailing.driver.exception;

import java.util.Map;

/** T10 — 409 TOMBSTONED_RESERVATION (contract §1.2): reserve tới sau release cho cùng reservationId. */
public class TombstonedReservationException extends RuntimeException {
    private final Map<String, Object> details;

    public TombstonedReservationException(String message, Map<String, Object> details) {
        super(message);
        this.details = details;
    }

    public Map<String, Object> details() {
        return details;
    }
}
