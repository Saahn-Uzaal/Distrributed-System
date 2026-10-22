package com.ridehailing.driver.security;

import com.ridehailing.driver.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * T07-1 (F01/N10) — kiểm tra X-Internal-Token cho /internal/** (contract §4).
 * So sánh constant-time với env INTERNAL_API_TOKEN. Hợp lệ -> đặt principal ROLE_INTERNAL và
 * tiếp tục chain (rule hasRole("INTERNAL") enforce phía sau). Thiếu/sai -> 401 JSON
 * INVALID_INTERNAL_CREDENTIAL, KHÔNG dispatch tới controller.
 * Matcher chạy trên path đã decode/normalize bởi StrictHttpFirewall — biến thể URL
 * (;x, %69nternal...) không còn bỏ qua được kiểm tra này.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    // SS7 bỏ PathPatternRequestMatcher — dùng PathPattern trên servletPath (đã decode/normalize
    // bởi StrictHttpFirewall trước khi filter này chạy).
    private static final PathPattern INTERNAL =
            PathPatternParser.defaultInstance.parse("/internal/**");

    private final String internalToken;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    /** N08 (T07-12): fail-fast khi INTERNAL_API_TOKEN thiếu hoặc ngắn hơn 16 ký tự —
     *  service không được khởi động với credential internal yếu/rỗng. */
    public InternalTokenFilter(String internalToken, tools.jackson.databind.ObjectMapper objectMapper) {
        if (internalToken == null || internalToken.isBlank() || internalToken.length() < 16) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN must be set and at least 16 characters long. "
                            + "Refusing to start driver-service with a missing or weak internal API token.");
        }
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!INTERNAL.matches(PathContainer.parsePath(request.getServletPath()))) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-Internal-Token");
        boolean ok = internalToken != null && !internalToken.isBlank() && provided != null
                && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        internalToken.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            String correlationId = CorrelationIdFilter.resolve(request);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setHeader("X-Correlation-ID", correlationId);
            objectMapper.writeValue(response.getOutputStream(),
                    new ApiError("INVALID_INTERNAL_CREDENTIAL", "Missing or invalid X-Internal-Token.", correlationId, null));
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new InternalAuthentication());
        chain.doFilter(request, response);
    }
}
