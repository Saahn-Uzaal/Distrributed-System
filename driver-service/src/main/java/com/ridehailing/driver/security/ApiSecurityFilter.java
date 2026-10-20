package com.ridehailing.driver.security;

import com.ridehailing.driver.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * T07 — security filter driver-service (contract api-contracts.md §1/§2/§3.2/§4):
 * <ul>
 *   <li>{@code /actuator/**} : mở, không auth (health access hợp lý).</li>
 *   <li>{@code /internal/**} : yêu cầu header {@code X-Internal-Token} = env {@code INTERNAL_API_TOKEN},
 *       so sánh constant-time; thiếu/sai -> 401 {@code INVALID_INTERNAL_CREDENTIAL}.</li>
 *   <li>{@code /api/drivers/**} : yêu cầu JWT RS256 hợp lệ + role DRIVER (mọi endpoint §3.2);
 *       token sai/hết hạn -> 401 {@code UNAUTHORIZED}, role khác -> 403 {@code FORBIDDEN}.</li>
 * </ul>
 * Chưa có controller nghiệp vụ (T09+) — filter chặn trước dispatch; probe test T07 dựa vào đây.
 */
@Component
@Order(2)
public class ApiSecurityFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authenticatedUser";

    private final JwtVerifier jwt;
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public ApiSecurityFilter(JwtVerifier jwt,
                             @Value("${internal.api-token:}") String internalToken,
                             ObjectMapper objectMapper) {
        this.jwt = jwt;
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }
        if (uri.startsWith("/internal/")) {
            requireInternalToken(request, response, chain);
            return;
        }
        if (uri.startsWith("/api/drivers/")) {
            requireJwtWithRole(request, response, chain, Role.DRIVER);
            return;
        }
        chain.doFilter(request, response);
    }

    private void requireInternalToken(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String provided = request.getHeader("X-Internal-Token");
        boolean ok = internalToken != null && !internalToken.isBlank() && provided != null
                && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        internalToken.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_INTERNAL_CREDENTIAL", "Missing or invalid X-Internal-Token.");
        } else {
            // Credential hợp lệ — tiếp tục dispatch (handler nghiệp vụ thuộc T10/T11).
            chain.doFilter(request, response);
        }
    }

    private void requireJwtWithRole(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Role required)
            throws IOException, ServletException {
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
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "Missing or invalid bearer token.");
        } else if (principal.role() != required) {
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    required + " role is required for this endpoint.");
        } else {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            chain.doFilter(request, response);
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(code, message, CorrelationIdFilter.resolve(request), null));
    }
}
