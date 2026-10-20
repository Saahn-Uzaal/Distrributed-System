package com.ridehailing.identity.security;

// Spring Boot 4: Jackson 3 (tools.jackson)
import tools.jackson.databind.ObjectMapper;
import com.ridehailing.identity.exception.ApiError;
import com.ridehailing.identity.exception.UnauthorizedException;
import com.ridehailing.identity.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * T06 — verify JWT RS256 cho GET /api/auth/me (contract §1/§3.1).
 * register/login là public, không qua filter này. Principal được đặt vào request attribute
 * {@link #PRINCIPAL_ATTRIBUTE}; controller chỉ tin principal từ chữ ký token.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authenticatedUser";
    private static final String ME_PATH = "/api/auth/me";

    private final JwtService jwt;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwt, ObjectMapper objectMapper) {
        this.jwt = jwt;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ME_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        AuthenticatedUser principal = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                principal = jwt.authenticate(header.substring(7).trim());
            } catch (UnauthorizedException ignored) {
                // token sai chữ ký / hết hạn / sai iss-aud -> 401 bên dưới
            }
        }
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(),
                    new ApiError("UNAUTHORIZED", "Missing or invalid bearer token.", correlationId(request), null));
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    /** X-Correlation-ID theo contract §5: header hợp lệ = UUID, thiếu/sai định dạng -> server sinh UUID mới. */
    public static String correlationId(HttpServletRequest request) {
        return CorrelationIdFilter.resolve(request);
    }
}
