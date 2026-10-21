package com.ridehailing.driver.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * T07-1 (F01/N10) — principal tổng hợp cho /internal/** sau khi X-Internal-Token được xác minh
 * bởi InternalTokenFilter. Authority ROLE_INTERNAL để rule hasRole("INTERNAL") enforce trong chain.
 */
public final class InternalAuthentication implements Authentication {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"));
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "internal";
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) {
        // luôn authenticated — X-Internal-Token đã xác minh constant-time trước khi tạo principal
    }

    @Override
    public String getName() {
        return "internal";
    }
}
