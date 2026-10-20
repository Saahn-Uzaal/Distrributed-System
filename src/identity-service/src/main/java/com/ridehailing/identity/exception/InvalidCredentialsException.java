package com.ridehailing.identity.exception;

/** 401 INVALID_CREDENTIALS — login sai email hoặc password (cùng message, không lộ chi tiết). */
public class InvalidCredentialsException extends RuntimeException {

    public static final String MESSAGE = "Invalid email or password.";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
