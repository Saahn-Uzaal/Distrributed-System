package com.ridehailing.trip.exception;

/** T12 — 404 NOT_FOUND (contract §1.2): trip không tồn tại HOẶC caller không có quyền xem
 * (chính sách nhất quán: không lộ sự tồn tại resource của người khác). */
public class TripNotFoundException extends RuntimeException {
    public TripNotFoundException(String message) {
        super(message);
    }
}
