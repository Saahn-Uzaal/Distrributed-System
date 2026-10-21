package com.ridehailing.driver.security;

import com.ridehailing.driver.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** T07-1 (F01/N10) — 401 JSON ApiError theo contract §1.1 + header X-Correlation-ID (§5). */
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper json;

    public JsonAuthEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        String correlationId = CorrelationIdFilter.resolve(request);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setHeader("X-Correlation-ID", correlationId);
        json.writeValue(response.getOutputStream(),
                new ApiError("UNAUTHORIZED", "Missing or invalid bearer token.", correlationId, null));
    }
}
