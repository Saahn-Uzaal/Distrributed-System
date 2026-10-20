package com.ridehailing.identity.web.dto;

/** POST /api/auth/register response (contract §3.1): body bọc trong trường "user". */
public record RegisterResponse(UserResponse user) {
}
