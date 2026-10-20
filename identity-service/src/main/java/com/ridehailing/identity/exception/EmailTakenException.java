package com.ridehailing.identity.exception;

/** 409 EMAIL_TAKEN — register với email đã tồn tại (contract §1.2). */
public class EmailTakenException extends RuntimeException {

    public EmailTakenException(String message) {
        super(message);
    }
}
