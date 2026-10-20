package com.ridehailing.driver.security;

import java.util.UUID;

/** T07 — principal lấy từ chữ ký JWT (contract §1: không tin userId do client tự gửi). */
public record AuthenticatedUser(UUID userId, String email, Role role) {
}
