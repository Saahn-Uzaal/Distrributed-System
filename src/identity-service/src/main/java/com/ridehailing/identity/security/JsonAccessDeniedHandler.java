package com.ridehailing.identity.security;

import com.ridehailing.identity.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/** T07-1 (F01/N10) — 403 JSON ApiError theo contract §1.2 + header X-Correlation-ID (§5). */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper json;

    public JsonAccessDeniedHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        String correlationId = CorrelationIdFilter.resolve(request);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setHeader("X-Correlation-ID", correlationId);
        json.writeValue(response.getOutputStream(),
                new ApiError("FORBIDDEN", "Insufficient role for this endpoint.", correlationId, null));
    }
}
