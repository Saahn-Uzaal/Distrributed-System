package com.ridehailing.identity.web;

import com.ridehailing.identity.security.AuthenticatedUser;
import com.ridehailing.identity.security.JwtAuthFilter;
import com.ridehailing.identity.service.UserService;
import com.ridehailing.identity.web.dto.LoginRequest;
import com.ridehailing.identity.web.dto.LoginResponse;
import com.ridehailing.identity.web.dto.RegisterRequest;
import com.ridehailing.identity.web.dto.RegisterResponse;
import com.ridehailing.identity.web.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** T06 — /api/auth: register (public), login (public), me (JWT) theo contract api-contracts.md §3.1. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(new RegisterResponse(users.register(req)));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(users.login(req));
    }

    @GetMapping("/me")
    public UserResponse me(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser principal) {
        return users.profile(principal.userId());
    }
}
