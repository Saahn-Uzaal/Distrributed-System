package com.ridehailing.driver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.ridehailing.driver.logging.JsonLogLayout;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
        layout.setService("driver-service");

        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        MDC.put("eventType", "http_request");
        MDC.put("result", "200");
        MDC.put("durationMs", "12");
        try {
            // logback 1.5.x: constructor (fqcn, Logger, level, message, throwable, args)
            Logger logger = (Logger) LoggerFactory.getLogger("com.ridehailing.driver.security.CorrelationIdFilter");
            LoggingEvent event = new LoggingEvent(
                    logger.getName(), logger, Level.INFO,
                    "request method=GET path=/api/drivers/me/profile status=200 correlationId=" + correlationId, null, new Object[0]);
            String line = layout.doLayout(event);

            Map<String, Object> m = parse(line); // parse được -> JSON hợp lệ
            assertThat(m.get("timestamp")).asString().endsWith("Z"); // UTC ISO-8601
            assertThat(m).containsKeys(
                    "level", "service", "instanceId", "correlationId", "eventType", "result", "durationMs", "message");
            assertThat(m.get("level")).isEqualTo("INFO");
            assertThat(m.get("service")).isEqualTo("driver-service");
            assertThat(m.get("correlationId")).isEqualTo(correlationId);
            assertThat(m.get("eventType")).isEqualTo("http_request");
            assertThat(m.get("result")).isEqualTo("200");
            assertThat(m.get("durationMs")).isEqualTo("12");
        } finally {
            MDC.clear();
        }
    }

    /** Q01 (T07-13): secret chỉ được phép nằm trong message — toàn bộ các field khác của output
     *  (quét trên JSON đã parse) không chứa token mẫu, password mẫu hay chuỗi "Bearer ". */
    @Test
    void layoutDoesNotCreateTopLevelSecretFields() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("driver-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.Logger");
        LoggingEvent event = new LoggingEvent(
                logger.getName(), logger, Level.INFO,
                "Authorization: Bearer secret-token-123 password=sample-password-456", null, new Object[0]);
        Map<String, Object> m = parse(layout.doLayout(event));
        // Secret chỉ có thể nằm trong message (nếu app code ghi log) — không bao giờ thành field riêng.
        assertThat(m).doesNotContainKeys("token", "password", "authorization");
        assertNoSecretsOutsideMessage(m);
    }

    /** Q01 (T07-13): toàn bộ chuỗi output của event bình thường (không secret trong message)
     *  không chứa token mẫu, password mẫu hay chuỗi "Bearer ". */
    @Test
    void normalEventOutputContainsNoSecrets() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("driver-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.SecretScanProbe");
        MDC.put("correlationId", UUID.randomUUID().toString());
        MDC.put("eventType", "http_request");
        MDC.put("result", "200");
        try {
            LoggingEvent event = new LoggingEvent(
                    logger.getName(), logger, Level.INFO,
                    "request method=GET path=/api/drivers/me/profile status=200", null, new Object[0]);
            String line = layout.doLayout(event);
            assertThat(line).doesNotContain("secret-token-123");
            assertThat(line).doesNotContain("sample-password-456");
            assertThat(line).doesNotContain("Bearer ");
        } finally {
            MDC.clear();
        }
    }

    private static void assertNoSecretsOutsideMessage(Map<String, Object> m) {
        List<String> secrets = List.of("secret-token-123", "sample-password-456", "Bearer ");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if ("message".equals(e.getKey())) {
                continue; // secret chỉ được phép xuất hiện trong message.
            }
            String value = String.valueOf(e.getValue());
            for (String secret : secrets) {
                assertThat(value).as("field %s must not contain %s", e.getKey(), secret).doesNotContain(secret);
            }
        }
    }

    @Test
    void layoutEscapesQuotesInMessage() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("driver-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.Logger");
        LoggingEvent event = new LoggingEvent(
                logger.getName(), logger, Level.INFO, "value with \"quotes\" and \\ backslash", null, new Object[0]);
        Map<String, Object> m = parse(layout.doLayout(event)); // parse được -> escape đúng
        assertThat(m.get("message")).isEqualTo("value with \"quotes\" and \\ backslash");
    }

    /** F20/Q02 — framing qua OutputStreamAppender thật: 3 event -> đúng 3 dòng, mỗi dòng parse JSON. */
    @Test
    void appenderFramesEachEventOnItsOwnLine() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("driver-service");

        Logger logger = (Logger) LoggerFactory.getLogger("com.example.FramingProbe");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.setName("FRAMING_PROBE");
        appender.setOutputStream(out);
        appender.setLayout(layout);
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 1; i <= 3; i++) {
                MDC.put("correlationId", UUID.randomUUID().toString());
                MDC.put("eventType", "http_request");
                MDC.put("result", String.valueOf(200 + i));
                logger.info("probe event {}", i);
            }
        } finally {
            MDC.clear();
            logger.detachAndStopAllAppenders();
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("}{"); // không còn \"}{\"timestamp\"
        List<String> lines = Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            Map<String, Object> m = parse(line); // mỗi dòng parse được bằng JSON
            assertThat(m.get("timestamp")).asString().endsWith("Z");
        }
    }

    /** F21 — stack trace đầy đủ: exception có cause -> output chứa class cause + frame \"at ...\". */
    @Test
    void layoutIncludesFullStackTraceWithCause() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("driver-service");
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.StackTraceProbe");
        LoggingEvent event = new LoggingEvent(
                logger.getName(), logger, Level.ERROR, "boom",
                new IllegalStateException("outer", new IllegalArgumentException("inner-cause")), new Object[0]);
        Map<String, Object> m = parse(layout.doLayout(event));
        String stackTrace = (String) m.get("stackTrace");
        assertThat(stackTrace).contains("IllegalArgumentException"); // tên class cause
        assertThat(stackTrace).contains("Caused by:");
        assertThat(stackTrace).containsPattern("\\bat [a-zA-Z0-9.$]+\\("); // ít nhất một frame "at ..."
    }
}
