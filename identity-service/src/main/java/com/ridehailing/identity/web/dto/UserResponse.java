package com.ridehailing.identity.web.dto;

import com.ridehailing.identity.domain.Role;

import java.util.UUID;

/** Thông tin user công khai — không bao giờ trả passwordHash (contract §3.1). */
public record UserResponse(UUID id, String email, String fullName, Role role) {
}
