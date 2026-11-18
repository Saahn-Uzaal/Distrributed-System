package com.ridehailing.identity.web.dto;

/** POST /api/auth/login response (contract §3.1). */
public record LoginResponse(String accessToken, long expiresIn, UserResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[accessToken=[REDACTED], expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}
