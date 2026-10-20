package com.ridehailing.identity.exception;

/** 401 UNAUTHORIZED — token sai chữ ký / hết hạn / sai issuer-audience, hoặc không còn user khớp. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
