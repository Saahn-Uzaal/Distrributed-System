package com.ridehailing.trip.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * T07-1 (F01/N10) — JWT principal trong SecurityFilterChain.
 * Authority {@code ROLE_<role>} để hasRole("PASSENGER")/hasRole("DRIVER") hoạt động
 * trong authorizeHttpRequests.
 */
public final class JwtAuthentication implements Authentication {

    private final AuthenticatedUser user;

    public JwtAuthentication(AuthenticatedUser user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
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
        // luôn authenticated — principal đã verify chữ ký RS256 trước khi tạo token này
    }

    @Override
    public String getName() {
        return user.email();
    }
}
