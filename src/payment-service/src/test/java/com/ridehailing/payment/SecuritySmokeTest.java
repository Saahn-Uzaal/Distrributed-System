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
                "app.flyway.enabled=false"
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

    @Test
    void invalidCorrelationHeaderGetsServerGeneratedUuid() throws Exception {
        Resp resp = get("/api/does-not-exist", "not-a-uuid");
        Object correlationId = resp.body().get("correlationId");
        assertThat(correlationId).isInstanceOf(String.class);
        assertThat(UUID.fromString((String) correlationId)).isNotNull(); // hợp lệ UUID, khác giá trị sai
        assertThat(correlationId).isNotEqualTo("not-a-uuid");
    }
}
