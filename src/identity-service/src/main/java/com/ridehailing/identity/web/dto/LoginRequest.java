package com.ridehailing.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/auth/login (contract §3.1). Không enforce min password ở đây: sai credentials -> 401, không 400. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String password) {
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=[REDACTED]]";
    }
}
