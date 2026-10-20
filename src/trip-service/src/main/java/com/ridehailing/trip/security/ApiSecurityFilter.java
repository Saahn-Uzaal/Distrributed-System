package com.ridehailing.trip.security;

import com.ridehailing.trip.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * T07 — security filter trip-service (contract api-contracts.md §1/§2/§3.3/§3.4):
 * <ul>
 *   <li>{@code /actuator/**} : mở, không auth (health access hợp lý).</li>
 *   <li>{@code /api/trips/**}, {@code /api/notifications/**} : yêu cầu JWT RS256 hợp lệ;
 *       role theo sub-path: POST quote/create -> PASSENGER; POST accept/reject/start/complete
 *       -> DRIVER (403 FORBIDDEN nếu sai); các path còn lại chấp nhận mọi role đã xác thực.</li>
 * </ul>
 * trip-service không có internal REST API trong contract §4 — chỉ driver-service có.
 * Chưa có controller nghiệp vụ (T12+) — filter chặn trước dispatch; probe test T07 dựa vào đây.
 */
@Component
@Order(2)
public class ApiSecurityFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "authenticatedUser";

    private static final java.util.regex.Pattern DRIVER_ACTIONS =
            java.util.regex.Pattern.compile("^/api/trips/[^/]+/(accept|reject|start|complete)$");

    private final JwtVerifier jwt;
    private final ObjectMapper objectMapper;

    public ApiSecurityFilter(JwtVerifier jwt, ObjectMapper objectMapper) {
        this.jwt = jwt;
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
        // R02 fix: bảo vệ cả path gốc lẫn subpath bằng matcher nhất quán
        // (trước đó /api/notifications không có slash cuối đi thẳng tới dispatcher).
        if (uri.startsWith("/api/trips/") || uri.equals("/api/trips")
                || uri.startsWith("/api/notifications/") || uri.equals("/api/notifications")) {
            requireJwtWithRole(request, response, chain, requiredRole(request.getMethod(), uri));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Role bắt buộc theo sub-path (contract §3.3): null = mọi role đã xác thực. */
    private static Role requiredRole(String method, String uri) {
        if (!"POST".equalsIgnoreCase(method)) {
            return null; // GET list/detail/events + notifications: passenger và driver đều xem được
        }
        if (uri.equals("/api/trips/quote") || uri.equals("/api/trips")) {
            return Role.PASSENGER;
        }
        if (DRIVER_ACTIONS.matcher(uri).matches()) {
            return Role.DRIVER;
        }
        return null; // POST cancel: owner = passenger, nhưng filter không phân biệt ở tầng này
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
        } else if (required != null && principal.role() != required) {
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
