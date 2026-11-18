package com.ridehailing.identity.web.dto;

import com.ridehailing.identity.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /api/auth/register (contract §3.1). Client không thể tự đăng ký ADMIN — enum chỉ có 2 role.
 * F04: {@code role} bắt buộc (@NotNull -> thiếu/null trả 400, không ghi user vào DB).
 * Giới hạn password theo byte UTF-8 (BCrypt ≤ 72 byte) được enforce ở UserService,
 * @Size(max=100) ký tự chỉ là chặn thô cho password phía DTO.
 * fullName chỉ kiểm tra không rỗng tại đây; giới hạn 2–100 áp dụng sau trim ở UserService.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank String fullName,
        @NotNull Role role) {
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=[REDACTED], fullName=" + fullName + ", role=" + role + "]";
    }
}
