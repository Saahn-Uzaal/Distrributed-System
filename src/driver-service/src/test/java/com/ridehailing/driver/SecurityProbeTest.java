package com.ridehailing.driver;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T07 — security probe test driver-service trên HTTP thật (không cần DB ngoài: H2 in-memory).
 * Filter chặn trước dispatch, nên các probe dùng path contract §3.2/§4 để chứng minh:
 * token sai/expired -> 401; role sai -> 403; internal thiếu credential bị chặn;
 * correlation giữ qua HTTP test (contract §5).
 * T09 fix: DriverRepository phụ thuộc JdbcTemplate nên context cần DataSource —
 * dùng H2 in-memory (flyway tắt, không bảng nào được tạo; probe không chạm DB).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Flyway không auto-configure trong Boot 4; bean custom FlywayConfig
                // tắt bằng app.flyway.enabled=false.
                "spring.datasource.url=jdbc:h2:mem:driver_probe;DB_CLOSE_DELAY=-1",
                // N09: main yml không còn dev default password — H2 probe set giá trị dummy.
                "spring.datasource.password=probe_dummy_password",
                "app.flyway.enabled=false"
        })
class SecurityProbeTest {

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;
    private static final String INTERNAL_TOKEN = "test-internal-token-t07";

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-driver");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = KEYS_DIR.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
            SIGNER = new RSASSASigner(pair.getPrivate());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("internal.api-token", () -> INTERNAL_TOKEN);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    record Resp(int status, Map<String, Object> body) {
    }

    private Resp get(String path, String bearerToken, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private Resp put(String path, String internalTokenHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json");
        if (internalTokenHeader != null) {
            req.header("X-Internal-Token", internalTokenHeader);
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

    /** Ký token thật bằng private key test với claims tùy chỉnh (exp/nbf/aud/role). */
    private static String sign(Map<String, Object> claims) throws Exception {
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jws.sign(SIGNER);
        return jws.serialize();
    }

    private static Map<String, Object> baseClaims(String role) {
        long nowEpoch = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("email", "probe@example.com");
        claims.put("role", role);
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of(
                "identity-service", "driver-service", "trip-service", "matching-service", "payment-service"));
        claims.put("iat", nowEpoch - 60);
        claims.put("exp", nowEpoch + 3600);
        return claims;
    }

    private static String validDriverToken() throws Exception {
        return sign(baseClaims("DRIVER"));
    }

    @Test
    void missingTokenOnProtectedPathReturns401WithCorrelationEcho() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = get("/api/drivers/me/profile", null, correlation);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        long nowEpoch = Instant.now().getEpochSecond();
        Map<String, Object> claims = baseClaims("DRIVER");
        claims.put("exp", nowEpoch - 60); // đã hết hạn (skew 0s)
        Resp resp = get("/api/drivers/me/profile", sign(claims), null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void tamperedSignatureReturns401() throws Exception {
        String token = validDriverToken();
        String[] parts = token.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        byte[] original = sig.clone();
        sig[0] ^= 0x01; // lật bit 0 của byte đầu chữ ký
        assertThat(sig).isNotEqualTo(original); // assert byte thực sự đã đổi trước khi gọi endpoint
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        Resp resp = get("/api/drivers/me/profile", tampered, null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void audienceWithoutThisServiceReturns401() throws Exception {
        Map<String, Object> claims = baseClaims("DRIVER");
        claims.put("aud", List.of("identity-service", "trip-service")); // không có driver-service
        Resp resp = get("/api/drivers/me/profile", sign(claims), null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void passengerRoleOnDriverEndpointReturns403() throws Exception {
        Resp resp = get("/api/drivers/me/profile", sign(baseClaims("PASSENGER")), null);
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void validDriverTokenPassesFilterAndReachesDispatcher() throws Exception {
        // T09: dùng path không có mapping (không trùng method của endpoint hiện có —
        // trùng path khác method sẽ cho 405 chứ không 404) -> 404 JSON từ
        // GlobalExceptionHandler, chứng minh filter chấp nhận token hợp lệ và lỗi vẫn
        // mang correlationId (contract §5).
        String correlation = UUID.randomUUID().toString();
        Resp resp = get("/api/drivers/me/no-such-endpoint", validDriverToken(), correlation);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    @Test
    void internalEndpointWithoutCredentialReturns401() throws Exception {
        Resp resp = put("/internal/reservations/" + UUID.randomUUID(), null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
    }

    @Test
    void internalEndpointWithWrongCredentialReturns401() throws Exception {
        Resp resp = put("/internal/reservations/" + UUID.randomUUID(), "wrong-token");
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
    }

    @Test
    void internalEndpointWithCorrectCredentialPassesFilter() throws Exception {
        // T10: /internal/reservations/{id} đã có controller — dùng path không khớp mapping bất kỳ
        // (thêm sub-path) -> 404 JSON, chứng minh credential được chấp nhận và dispatch tới error handler.
        Resp resp = put("/internal/reservations/" + UUID.randomUUID() + "/no-such-subpath", INTERNAL_TOKEN);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingCorrelationHeaderGetsServerGeneratedUuid() throws Exception {
        Resp resp = get("/api/drivers/me/profile", null, null);
        Object correlationId = resp.body().get("correlationId");
        assertThat(correlationId).isInstanceOf(String.class);
        assertThat(UUID.fromString((String) correlationId)).isNotNull(); // hợp lệ UUID
    }

    @Test
    void healthIsOpenWithoutAuth() throws Exception {
        Resp resp = get("/actuator/health", null, null);
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("status")).isEqualTo("UP");
    }

    private Resp putJson(String path, Object body, String internalTokenHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json");
        if (internalTokenHeader != null) {
            req.header("X-Internal-Token", internalTokenHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private Resp delete(String path, String bearerToken, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path)).DELETE();
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    // ---------- T07-15 (Q03) + F01 — biến thể URL trên path bảo vệ không được bỏ qua auth ----------

    /** F01 repro: matrix parameter ';' trước đây lọt qua filter URI thô -> nay 400 từ StrictHttpFirewall. */
    @Test
    void matrixParameterOnInternalPathIsRejected() throws Exception {
        Resp resp = put("/internal;x/reservations/" + UUID.randomUUID(), null);
        assertThat(resp.status()).isEqualTo(400);

        Resp api = get("/api/drivers/me/profile;x", null, null);
        assertThat(api.status()).isEqualTo(400);
    }

    /** F01 repro: %69 decode thành 'i' -> /internal/... vẫn bắt buộc X-Internal-Token. */
    @Test
    void encodedInternalPathStillRequiresInternalCredential() throws Exception {
        Resp resp = put("/%69nternal/reservations/" + UUID.randomUUID(), null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
    }

    /** '//' và '/./': bị container/firewall chặn (400) hoặc vẫn enforce auth (401/403); không bao giờ tới handler. */
    @Test
    void doubleSlashAndDotSegmentVariantsDoNotBypassAuth() throws Exception {
        Resp dbl = put("/internal//reservations/" + UUID.randomUUID(), null);
        assertThat(dbl.status()).isIn(400, 401);

        Resp dot = get("/api/drivers/./me/profile", null, null);
        assertThat(dot.status()).isIn(400, 401, 403, 404);
    }

    /** Path chữ hoa: không match case-insensitive -> resource lạ (404 JSON), không phải bypass. */
    @Test
    void uppercasePathIsNotFoundNotBypass() throws Exception {
        Resp resp = get("/API/DRIVERS/me/profile", null, null);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
    }

    /** Trailing slash trên path bảo vệ: vẫn yêu cầu role DRIVER (không bypass bằng '/'). */
    @Test
    void trailingSlashOnProtectedPathStillRequiresAuth() throws Exception {
        Resp resp = get("/api/drivers/me/profile/", null, null);
        assertThat(resp.status()).isIn(401, 403, 404);
    }

    // ---------- T07-3 (F02) — NumericDate có phần giây lẻ ----------

    /** F02: exp = now + 0.5 s vẫn hợp lệ; nbf tương lai hoặc "nbf": null -> 401. */
    @Test
    void fractionalSecondExpAcceptedAndNbfFutureOrNullRejected() throws Exception {
        Map<String, Object> claims = baseClaims("DRIVER");
        claims.put("exp", Instant.now().toEpochMilli() / 1000.0 + 0.5); // now + 0.5 s
        Resp ok = get("/api/drivers/me/no-such-endpoint", sign(claims), null);
        assertThat(ok.status()).isEqualTo(404); // filter chấp nhận -> dispatcher (không có mapping)

        claims = baseClaims("DRIVER");
        claims.put("nbf", Instant.now().toEpochMilli() / 1000.0 + 0.9); // chưa hiệu lực
        Resp nbfFuture = get("/api/drivers/me/profile", sign(claims), null);
        assertThat(nbfFuture.status()).isEqualTo(401);
        assertThat(nbfFuture.body().get("code")).isEqualTo("UNAUTHORIZED");

        claims = baseClaims("DRIVER");
        claims.put("nbf", null); // có mặt nhưng null -> invalid (F02)
        Resp nbfNull = get("/api/drivers/me/profile", sign(claims), null);
        assertThat(nbfNull.status()).isEqualTo(401);
        assertThat(nbfNull.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    // ---------- T07-4 (F03) — JSON sai kiểu không được ép kiểu ngầm ----------

    /** F03: commandVersion 1.9 không được thành long 1 -> 400 INVALID_INPUT, DB không đổi. */
    @Test
    void fractionalCommandVersionRejected() throws Exception {
        Map<String, Object> body = reserveBody(1.9); // float thay vì integer
        Resp resp = putJson("/internal/reservations/" + UUID.randomUUID(), body, INTERNAL_TOKEN);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    /** F03: attempt dạng chuỗi "3" không được thành int 3 -> 400 INVALID_INPUT. */
    @Test
    void stringAttemptRejected() throws Exception {
        Map<String, Object> body = reserveBody(1);
        body.put("attempt", "3"); // String thay vì integer
        Resp resp = putJson("/internal/reservations/" + UUID.randomUUID(), body, INTERNAL_TOKEN);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    /** F03: latitude dạng chuỗi "10.7" không được thành double -> 400 INVALID_INPUT. */
    @Test
    void stringLatitudeRejected() throws Exception {
        Map<String, Object> body = reserveBody(1);
        body.put("pickup", Map.of("latitude", "10.7769", "longitude", 106.7009));
        Resp resp = putJson("/internal/reservations/" + UUID.randomUUID(), body, INTERNAL_TOKEN);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    private static Map<String, Object> reserveBody(Object commandVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", UUID.randomUUID().toString());
        body.put("commandVersion", commandVersion);
        body.put("tripId", UUID.randomUUID().toString());
        body.put("driverId", UUID.randomUUID().toString());
        body.put("attempt", 1);
        body.put("expiresAt", Instant.now().plusSeconds(3600).toString());
        body.put("pickup", Map.of("latitude", 10.7769, "longitude", 106.7009));
        body.put("destination", Map.of("latitude", 10.8000, "longitude", 106.7200));
        body.put("distanceM", 3500L);
        body.put("fareVnd", 45000L);
        return body;
    }

    // ---------- T07-5 (F04) — lỗi phía client không được gom thành 500 ----------

    /** F04: sai HTTP method -> 405 METHOD_NOT_ALLOWED kèm correlationId. */
    @Test
    void wrongMethodReturns405WithCorrelation() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = delete("/api/drivers/me/profile", validDriverToken(), correlation);
        assertThat(resp.status()).isEqualTo(405);
        assertThat(resp.body().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    /** F04: sai Content-Type -> 415 UNSUPPORTED_MEDIA_TYPE kèm correlationId. */
    @Test
    void wrongContentTypeReturns415() throws Exception {
        String correlation = UUID.randomUUID().toString();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/internal/reservations/" + UUID.randomUUID()))
                .PUT(HttpRequest.BodyPublishers.ofString("not-json"))
                .header("Content-Type", "text/plain")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("X-Correlation-ID", correlation)
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(415);
        Map<String, Object> body = parse(resp.body());
        assertThat(body.get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(body.get("correlationId")).isEqualTo(correlation);
    }

    // ---------- L03/L04 — request bị Spring Security chặn vẫn có log truy cập; firewall trả ApiError ----------

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAccessLogAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.ridehailing.driver.security.CorrelationIdFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAccessLogAppender(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.ridehailing.driver.security.CorrelationIdFilter.class)).detachAppender(appender);
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
            Resp resp = get("/api/probe;x", null, correlation);
            assertThat(resp.status()).isEqualTo(400);
            assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
            assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
            assertThat(resp.body()).doesNotContainKey("timestamp");
            assertThat(accessLogged(appender, correlation, "400")).isTrue();
        } finally {
            detachAccessLogAppender(appender);
        }
    }

    /** L03: request bị từ chối ở tầng security (401) vẫn có dòng log truy cập kèm correlationId. */
    @org.junit.jupiter.api.Test
    void unauthorizedRequestIsAccessLogged() throws Exception {
        var appender = attachAccessLogAppender();
        try {
            String correlation = UUID.randomUUID().toString();
            Resp resp = get("/api/drivers/me/profile", null, correlation);
            assertThat(resp.status()).isEqualTo(401);
            assertThat(accessLogged(appender, correlation, "401")).isTrue();
        } finally {
            detachAccessLogAppender(appender);
        }
    }
}
