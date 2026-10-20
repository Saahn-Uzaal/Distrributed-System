package com.ridehailing.driver.exception;

/** T09 — 409 PLATE_TAKEN (chốt T09, contract §1.2): plateNumber đã tồn tại trong driver_db. */
public class PlateTakenException extends RuntimeException {
    public PlateTakenException(String message) {
        super(message);
    }
}
