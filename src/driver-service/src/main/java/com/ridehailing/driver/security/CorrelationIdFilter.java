package com.ridehailing.driver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * T07 — correlation ID theo contract api-contracts.md §5:
 * header {@code X-Correlation-ID} hợp lệ = UUID; thiếu hoặc sai định dạng -> server tự sinh
 * UUID mới (KHÔNG reject request). Id được đặt vào request attribute + MDC để mọi log và
 * error response của request dùng chung một id.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * L03: chạy TRƯỚC Spring Security (springSecurityFilterChain có order -100). Nếu chạy sau,
     * request bị chặn ở tầng security (401/403, firewall 400) không có dòng log truy cập
     * và không có correlationId trong MDC.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    public static final String ATTRIBUTE = "correlationId";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request);
        request.setAttribute(ATTRIBUTE, id);
        MDC.put("correlationId", id);
        // R05 fix: structured fields theo spec §11 — eventType trong suốt request;
        // result/durationMs được set trước dòng log cuối (vẫn còn trong MDC).
        MDC.put("eventType", "http_request");
        // N12: trả X-Correlation-ID trong mọi response — set trước chain để cả handler lỗi
        // và controller đều giữ header.
        response.setHeader("X-Correlation-ID", id);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            // N11: dòng log truy cập luôn giữ eventType=http_request dù business code đã đổi MDC.
            MDC.put("eventType", "http_request");
            MDC.put("result", String.valueOf(response.getStatus()));
            MDC.put("durationMs", String.valueOf(durationMs));
            log.info("request method={} path={} status={} correlationId={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), id);
            MDC.clear();
        }
    }

    /** Dùng lại ở filter/handler khác: attribute (đã set bởi filter này) hoặc resolve từ header. */
    public static String resolve(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTRIBUTE);
        if (attr instanceof String s && !s.isBlank()) {
            return s;
        }
        String id = request.getHeader("X-Correlation-ID");
        // N13: chuẩn hóa — UUID.fromString(...).toString() để log, error body và outbox dùng
        // cùng một giá trị (vd "1-2-3-4-5" -> "00001234-0000-0000-0000-000000000045").
        return isValidUuid(id) ? UUID.fromString(id.trim()).toString() : UUID.randomUUID().toString();
    }

    public static boolean isValidUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
