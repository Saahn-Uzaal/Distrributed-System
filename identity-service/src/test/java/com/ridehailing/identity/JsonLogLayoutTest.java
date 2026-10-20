package com.ridehailing.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.ridehailing.identity.logging.JsonLogLayout;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R05 fix — chứng minh JsonLogLayout phát JSON hợp lệ với các field bắt buộc của spec §11
 * (timestamp UTC, service, instanceId, level, eventType, correlationId, result, durationMs)
 * và không tạo top-level field chứa secret.
 */
class JsonLogLayoutTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) throws Exception {
        return JSON.readValue(line.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    @Test
    void layoutEmitsStructuredJsonWithRequiredFields() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("identity-service");

        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("eventType", "http_request");
        MDC.put("result", "201");
        MDC.put("durationMs", "35");
        try {
            // logback 1.5.x: constructor (fqcn, Logger, level, message, throwable, args)
            Logger logger = (Logger) LoggerFactory.getLogger("com.ridehailing.identity.security.CorrelationIdFilter");
            LoggingEvent event = new LoggingEvent(
                    logger.getName(), logger, Level.INFO,
                    "request method=POST path=/api/auth/register status=201 correlationId=" + correlationId, null, new Object[0]);
            String line = layout.doLayout(event);

            Map<String, Object> m = parse(line); // parse được -> JSON hợp lệ
            assertThat(m.get("timestamp")).asString().endsWith("Z"); // UTC ISO-8601
            assertThat(m).containsKeys(
                    "level", "service", "instanceId", "correlationId", "eventType", "result", "durationMs", "message");
            assertThat(m.get("level")).isEqualTo("INFO");
            assertThat(m.get("service")).isEqualTo("identity-service");
            assertThat(m.get("correlationId")).isEqualTo(correlationId);
            assertThat(m.get("eventType")).isEqualTo("http_request");
            assertThat(m.get("result")).isEqualTo("201");
            assertThat(m.get("durationMs")).isEqualTo("35");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void layoutDoesNotCreateTopLevelSecretFields() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("identity-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.Logger");
        LoggingEvent event = new LoggingEvent(
                logger.getName(), logger, Level.INFO,
                "Authorization: Bearer secret-token-123 password=abc", null, new Object[0]);
        Map<String, Object> m = parse(layout.doLayout(event));
        // Secret chỉ có thể nằm trong message (nếu app code ghi log) — không bao giờ thành field riêng.
        assertThat(m).doesNotContainKeys("token", "password", "authorization");
    }

    @Test
    void layoutEscapesQuotesInMessage() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("identity-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.Logger");
        LoggingEvent event = new LoggingEvent(
                logger.getName(), logger, Level.INFO, "value with \"quotes\" and \\ backslash", null, new Object[0]);
        Map<String, Object> m = parse(layout.doLayout(event)); // parse được -> escape đúng
        assertThat(m.get("message")).isEqualTo("value with \"quotes\" and \\ backslash");
    }
}
