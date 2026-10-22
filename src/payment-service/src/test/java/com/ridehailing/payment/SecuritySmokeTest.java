package com.ridehailing.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T07 — smoke test payment-service trên HTTP thật (không cần DB: exclude flyway/jdbc).
 * payment-service không có REST API trong contract §3/§4 — nghiệm thu T07 ở đây là:
 * health mở không auth + mọi lỗi JSON mang correlationId (contract §1.1/§5).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // R04 fix: Boot 4 đã chuyển DataSourceAutoConfiguration sang
                // org.springframework.boot.jdbc.autoconfigure — tên cũ bị bỏ qua im lặng,
                // test vẫn kết nối DB dev. Flyway không auto-configure trong Boot 4;
                // bean custom FlywayConfig tắt bằng app.flyway.enabled=false.
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                // N09: datasource bị exclude — dummy password để RequiredDbPasswordValidator không chặn.
                "spring.datasource.password=smoke_no_db",
                "app.flyway.enabled=false",
                // C06: production yml khai báo group.readiness.include=db, nhưng test này
                // không có DataSource → không có contributor 'db' → Boot 4 validator fail.
                // Override group thành rỗng để context load được; /actuator/health tổng thể vẫn hoạt động.
                "management.endpoint.health.group.readiness.include="
        })
class SecuritySmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    record Resp(int status, Map<String, Object> body) {
    }

    private Resp get(String path, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
        return m;
    }

    @Test
    void healthIsOpenWithoutAuth() throws Exception {
        Resp resp = get("/actuator/health", null);
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("status")).isEqualTo("UP");
    }

    @Test
    void unknownPathReturns404JsonWithCorrelationEcho() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = get("/api/does-not-exist", correlation);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    private Resp post(String path, byte[] bodyBytes, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    /** N03 (T07-11): body > 64 KiB -> 413 PAYLOAD_TOO_LARGE JSON, correlationId echo. */
    @Test
    void oversizedBodyReturns413PayloadTooLarge() throws Exception {
        String correlation = UUID.randomUUID().toString();
        byte[] bytes = ("{\"padding\":\"" + "a".repeat(70_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        Resp resp = post("/api/does-not-exist", bytes, correlation);
        assertThat(resp.status()).isEqualTo(413);
        assertThat(resp.body().get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    @Test
    void invalidCorrelationHeaderGetsServerGeneratedUuid() throws Exception {
        Resp resp = get("/api/does-not-exist", "not-a-uuid");
        Object correlationId = resp.body().get("correlationId");
        assertThat(correlationId).isInstanceOf(String.class);
        assertThat(UUID.fromString((String) correlationId)).isNotNull(); // hợp lệ UUID, khác giá trị sai
        assertThat(correlationId).isNotEqualTo("not-a-uuid");
    }

    // ---------- L03/L04 — request bị Spring Security chặn vẫn có log truy cập; firewall trả ApiError ----------

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAccessLogAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.ridehailing.payment.security.CorrelationIdFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAccessLogAppender(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.ridehailing.payment.security.CorrelationIdFilter.class)).detachAppender(appender);
    }

    /** Dòng log truy cập được ghi sau khi response đã gửi — chờ tối đa 3 s. */
    private static boolean accessLogged(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
            String correlation, String status) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean found = new java.util.ArrayList<>(appender.list).stream().anyMatch(e ->
                    correlation.equals(e.getMDCPropertyMap().get("correlationId"))
                            && status.equals(e.getMDCPropertyMap().get("result"))
                            && "http_request".equals(e.getMDCPropertyMap().get("eventType")));
            if (found) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** L04: URL bị StrictHttpFirewall chặn -> 400 ApiError có correlationId (không phải body mặc định Spring). L03: vẫn có log. */
    @org.junit.jupiter.api.Test
    void firewallRejectionReturnsApiErrorAndIsAccessLogged() throws Exception {
        var appender = attachAccessLogAppender();
        try {
            String correlation = UUID.randomUUID().toString();
            Resp resp = get("/api/probe;x", correlation);
            assertThat(resp.status()).isEqualTo(400);
            assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
            assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
            assertThat(resp.body()).doesNotContainKey("timestamp");
            assertThat(accessLogged(appender, correlation, "400")).isTrue();
        } finally {
            detachAccessLogAppender(appender);
        }
    }
}
