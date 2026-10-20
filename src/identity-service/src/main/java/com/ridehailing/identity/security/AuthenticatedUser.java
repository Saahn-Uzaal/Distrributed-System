package com.ridehailing.identity.security;

import com.ridehailing.identity.domain.Role;

import java.util.UUID;

/**
 * T06 — principal lấy từ chữ ký JWT (contract api-contracts.md §1: không tin userId do client tự gửi).
 */
public record AuthenticatedUser(UUID userId, String email, Role role) {
}
