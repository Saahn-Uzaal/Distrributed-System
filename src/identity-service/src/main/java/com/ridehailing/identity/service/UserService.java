package com.ridehailing.identity.service;

import com.ridehailing.identity.domain.Role;
import com.ridehailing.identity.domain.User;
import com.ridehailing.identity.domain.UserRepository;
import com.ridehailing.identity.exception.EmailTakenException;
import com.ridehailing.identity.exception.InvalidCredentialsException;
import com.ridehailing.identity.exception.InvalidInputException;
import com.ridehailing.identity.exception.UnauthorizedException;
import com.ridehailing.identity.web.dto.LoginRequest;
import com.ridehailing.identity.web.dto.LoginResponse;
import com.ridehailing.identity.web.dto.RegisterRequest;
import com.ridehailing.identity.web.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ridehailing.identity.logging.AuditLog;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * T06 — register/login/me (contract api-contracts.md §3.1). BCrypt cho password, không bao giờ lưu plaintext.
 * F04: giới hạn password chốt theo **byte UTF-8 ≤ 72** (giới hạn đầu vào của BCrypt) — vượt trả
 * 400 INVALID_INPUT ở register và 401 INVALID_CREDENTIALS ở login (cùng message, không lộ email tồn tại).
 * fullName validate 2–100 ký tự **sau khi trim**. Không cắt/trim password.
 */
@Service
public class UserService {

    /** Giới hạn BCrypt: tối đa 72 byte UTF-8 cho password. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** R03 (T06-4): dummy hash để login vẫn chạy BCrypt khi email không tồn tại — thời gian xử lý tương đương,
     *  không lộ việc email có trong DB qua timing. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("dummy-password-for-timing-equalization");

    /** N11 — audit log: eventType riêng cho register/login; không ghi email đầy đủ hay password. */
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwt) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }

    public UserResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        // F03: existsByEmail là fast path; unique constraint DB (users_email_key) là cơ chế
        // bảo đảm cuối cùng cho race đồng thời — vi phạm unique key được map 409 ở GlobalExceptionHandler.
        if (users.existsByEmail(email)) {
            throw new EmailTakenException("Email is already registered.");
        }
        String password = req.password();
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            // F04: chặn trước khi BCrypt encoder ném exception -> 500
            throw new InvalidInputException("Password must be at most " + MAX_PASSWORD_BYTES + " UTF-8 bytes.", "password");
        }
        String fullName = req.fullName().trim();
        if (fullName.length() < 2 || fullName.length() > 100) {
            // F04: validate sau trim — " a" không được thành tài khoản tên một ký tự
            throw new InvalidInputException("fullName must be 2-100 characters after trimming.", "fullName");
        }
        // F05 (T06-1): từ chối ký tự điều khiển (\p{Cc}, đặc biệt \u0000) — PostgreSQL sẽ lỗi → 500.
        if (containsControlChar(fullName)) {
            throw new InvalidInputException("fullName must not contain control characters.", "fullName");
        }
        User user = new User(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(password),
                fullName,
                req.role(),
                Instant.now());
        users.save(user);
        // N11 audit: user_registered — chỉ userId + role, không email/password.
        AuditLog.info(log, AuditLog.mdc("eventType", "user_registered", "result", "SUCCESS"),
                "user registered userId={} role={}", user.getId(), user.getRole());
        return toResponse(user);
    }

    public LoginResponse login(LoginRequest req) {
        // F04: password > 72 byte UTF-8 không được gây 500 (BCrypt.matches ném exception);
        // trả cùng INVALID_CREDENTIALS cho mọi trường hợp — không lộ việc email có tồn tại.
        if (req.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidCredentialsException();
        }
        User user = users.findByEmail(normalizeEmail(req.email())).orElse(null);
        // R03 (T06-4): email không tồn tại vẫn chạy BCrypt với dummy hash — thời gian xử lý tương đương.
        if (user == null) {
            passwordEncoder.matches(req.password(), DUMMY_HASH);
        }
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // N11 audit: login_failed — message không chứa email đầy đủ hay password.
            AuditLog.info(log, AuditLog.mdc("eventType", "login_failed", "result", "FAILURE"),
                    "login failed (invalid credentials)");
            throw new InvalidCredentialsException();
        }
        // N11 audit: login_succeeded — chỉ userId + role.
        AuditLog.info(log, AuditLog.mdc("eventType", "login_succeeded", "result", "SUCCESS"),
                "login succeeded userId={} role={}", user.getId(), user.getRole());
        return new LoginResponse(jwt.issue(user), jwt.getExpiresInSeconds(), toResponse(user));
    }

    /** /me: principal từ JWT; đọc lại DB để lấy fullName (không có trong claims) và đảm bảo user còn tồn tại. */
    public UserResponse profile(UUID userId) {
        return users.findById(userId)
                .map(UserService::toResponse)
                .orElseThrow(() -> new UnauthorizedException("Token does not match a known user."));
    }

    /** F05: kiểm tra ký tự điều khiển Unicode (category Cc — U+0000–U+001F, U+007F–U+009F, ...). */
    static boolean containsControlChar(String s) {
        return s.codePoints().anyMatch(Character::isISOControl);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole());
    }
}
