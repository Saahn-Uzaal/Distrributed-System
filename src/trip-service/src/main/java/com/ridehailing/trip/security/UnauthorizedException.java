package com.ridehailing.trip.security;

/** T07 — 401 UNAUTHORIZED (token thiếu/sai chữ ký/hết hạn/sai iss-aud/claims sai kiểu). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
