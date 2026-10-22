package com.ridehailing.payment.exception;

/** N03 (T07-11) — request body vượt giới hạn 64 KiB -> 413 PAYLOAD_TOO_LARGE. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
