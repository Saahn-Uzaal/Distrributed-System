package com.ridehailing.trip.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * T07-1 (F01/N10) — JWT authentication trong SecurityFilterChain (thay ApiSecurityFilter cũ).
 * Bearer hợp lệ -> principal vào SecurityContext + request attribute {@link #PRINCIPAL_ATTRIBUTE}
 * (controller vẫn đọc qua @RequestAttribute như trước). Token thiếu/sai KHÔNG 401 ở đây;
 * lớp authorization trả 401 JSON (JsonAuthEntryPoint) — hành vi bên ngoài giữ nguyên.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authenticatedUser";

    private final JwtVerifier jwt;

    public JwtAuthenticationFilter(JwtVerifier jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthenticatedUser principal = jwt.authenticate(header.substring(7).trim());
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthentication(principal));
                request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            } catch (UnauthorizedException ignored) {
                // token sai chữ ký / hết hạn / sai iss-aud -> 401 ở lớp authorization
            }
        }
        chain.doFilter(request, response);
    }
}
