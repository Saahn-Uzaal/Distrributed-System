package com.ridehailing.driver.security;

import com.ridehailing.driver.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * L04 — URL bị StrictHttpFirewall chặn (';', %2F, %25, '..'...) trả ApiError theo contract §1.1
 * (400 INVALID_INPUT + correlationId + header X-Correlation-ID), thay cho body mặc định
 * {timestamp,status,error,path} của Spring Boot. WebSecurity tự dùng bean RequestRejectedHandler.
 */
public class JsonRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonRequestRejectedHandler.class);

    private final ObjectMapper objectMapper;

    public JsonRequestRejectedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException e) throws IOException {
        String correlationId = CorrelationIdFilter.resolve(request);
        log.warn("request rejected by firewall: {}", e.getMessage());
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setHeader("X-Correlation-ID", correlationId);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError("INVALID_INPUT", "Request URL contains characters that are not allowed.",
                        correlationId, null));
    }
}
