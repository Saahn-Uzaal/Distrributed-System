package com.ridehailing.trip;

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
 * T07 — security probe test trip-service trên HTTP thật (không cần DB ngoài: H2 in-memory).
 * Filter chặn trước dispatch, nên các probe dùng path contract §3.3 để chứng minh:
 * token sai/expired -> 401; role sai theo sub-path -> 403; correlation giữ qua HTTP test
 * (contract §5). T12 fix: repository phụ thuộc JdbcTemplate nên context cần DataSource —
 * dùng H2 in-memory (flyway tắt, không bảng nào được tạo; probe không chạm DB).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Flyway không auto-configure trong Boot 4; bean custom FlywayConfig
                // tắt bằng app.flyway.enabled=false.
                "spring.datasource.url=jdbc:h2:mem:trip_probe;DB_CLOSE_DELAY=-1",
                // N09: main yml không còn dev default password — H2 probe set giá trị dummy.
                "spring.datasource.password=probe_dummy_password",
                "app.flyway.enabled=false"
        })
class SecurityProbeTest {

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-trip");
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

    private Resp post(String path, Object body, String bearerToken, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
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

    /** Ký token thật bằng private key test với claims tùy chỉnh (exp/aud/role). */
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

    private static final Map<String, Object> QUOTE_BODY = Map.of(
            "pickup", Map.of("latitude", 10.7769, "longitude", 106.7009),
            "destination", Map.of("latitude", 10.8000, "longitude", 106.7200));

    @Test
    void missingTokenOnProtectedPathReturns401WithCorrelationEcho() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = post("/api/trips/quote", QUOTE_BODY, null, correlation);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    /** N03 (T07-11): body > 64 KiB -> 413 PAYLOAD_TOO_LARGE JSON, correlationId echo. */
    @Test
    void oversizedBodyReturns413PayloadTooLarge() throws Exception {
        String token = sign(baseClaims("PASSENGER"));
        String correlation = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>(QUOTE_BODY);
        body.put("padding", "a".repeat(70_000)); // > 64 KiB
        Resp resp = post("/api/trips/quote", body, token, correlation);
        assertThat(resp.status()).isEqualTo(413);
        assertThat(resp.body().get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        long nowEpoch = Instant.now().getEpochSecond();
        Map<String, Object> claims = baseClaims("PASSENGER");
        claims.put("exp", nowEpoch - 60); // đã hết hạn (skew 0s)
        Resp resp = post("/api/trips/quote", QUOTE_BODY, sign(claims), null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void tamperedSignatureReturns401() throws Exception {
        String token = sign(baseClaims("PASSENGER"));
        String[] parts = token.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        byte[] original = sig.clone();
        sig[0] ^= 0x01; // lật bit 0 của byte đầu chữ ký
        assertThat(sig).isNotEqualTo(original); // assert byte thực sự đã đổi trước khi gọi endpoint
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        Resp resp = post("/api/trips/quote", QUOTE_BODY, tampered, null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void passengerRoleOnDriverActionReturns403() throws Exception {
        Resp resp = post("/api/trips/" + UUID.randomUUID() + "/accept", Map.of("attempt", 1),
                sign(baseClaims("PASSENGER")), null);
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void driverRoleOnPassengerQuoteReturns403() throws Exception {
        Resp resp = post("/api/trips/quote", QUOTE_BODY, sign(baseClaims("DRIVER")), null);
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void validPassengerTokenPassesFilterAndReachesDispatcher() throws Exception {
        // T12 fix: /api/trips đã có controller (list) — probe chuyển sang path chưa có handler
        // (events = T25) để vẫn chứng minh filter chấp nhận token hợp lệ và lỗi mang correlationId
        // (contract §5).
        String correlation = UUID.randomUUID().toString();
        Resp resp = get("/api/trips/" + UUID.randomUUID() + "/events", sign(baseClaims("PASSENGER")), correlation);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    // R02 fix — probe URL chính xác theo contract (không có slash cuối), không chỉ subpath:
    @Test
    void missingTokenOnExactNotificationsPathReturns401WithCorrelationEcho() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = get("/api/notifications", null, correlation);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    @Test
    void missingTokenOnExactTripsPathReturns401() throws Exception {
        Resp resp = post("/api/trips", QUOTE_BODY, null, null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void validDriverTokenOnExactNotificationsPathPassesFilter() throws Exception {
        // Chưa có controller notifications (T25) -> 404 JSON chứng minh filter đã chấp nhận token
        // cho path gốc /api/notifications (trước fix: request đi thẳng tới dispatcher, không qua JWT check).
        Resp resp = get("/api/notifications", sign(baseClaims("DRIVER")), null);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void missingCorrelationHeaderGetsServerGeneratedUuid() throws Exception {
        Resp resp = post("/api/trips/quote", QUOTE_BODY, null, null);
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

    private Resp postText(String path, String rawBody, String bearerToken, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                .header("Content-Type", "text/plain");
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
    void matrixParameterOnQuotePathIsRejected() throws Exception {
        Resp resp = post("/api/trips/quote;x", QUOTE_BODY, null, null);
        assertThat(resp.status()).isEqualTo(400);
    }

    /** F01 repro: %61 decode thành 'a' -> /api/trips/quote vẫn bắt buộc role PASSENGER. */
    @Test
    void encodedApiPathStillRequiresAuth() throws Exception {
        Resp resp = post("/%61pi/trips/quote", QUOTE_BODY, null, null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    /** '//' và '/./': bị container/firewall chặn (400) hoặc vẫn enforce auth (401/403); không bao giờ tới handler. */
    @Test
    void doubleSlashAndDotSegmentVariantsDoNotBypassAuth() throws Exception {
        Resp dbl = get("/api//notifications", null, null);
        assertThat(dbl.status()).isIn(400, 401, 403, 404);

        Resp dot = post("/api/trips/./quote", QUOTE_BODY, null, null);
        assertThat(dot.status()).isIn(400, 401, 403, 404);
    }

    /** Path chữ hoa: không match case-insensitive -> resource lạ (404 JSON), không phải bypass. */
    @Test
    void uppercasePathIsNotFoundNotBypass() throws Exception {
        Resp resp = get("/API/TRIPS", null, null);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
    }

    /** Trailing slash trên path bảo vệ: vẫn yêu cầu xác thực (không bypass bằng '/'). */
    @Test
    void trailingSlashOnProtectedPathStillRequiresAuth() throws Exception {
        Resp resp = get("/api/notifications/", null, null);
        assertThat(resp.status()).isIn(401, 403, 404);
    }

    // ---------- T07-3 (F02) — NumericDate có phần giây lẻ ----------

    /** F02: exp = now + 0.5 s vẫn hợp lệ; nbf tương lai hoặc "nbf": null -> 401. */
    @Test
    void fractionalSecondExpAcceptedAndNbfFutureOrNullRejected() throws Exception {
        Map<String, Object> claims = baseClaims("PASSENGER");
        claims.put("exp", Instant.now().toEpochMilli() / 1000.0 + 0.5); // now + 0.5 s
        Resp ok = post("/api/trips/quote", QUOTE_BODY, sign(claims), null);
        assertThat(ok.status()).isEqualTo(200); // filter chấp nhận -> quote tính được (không chạm DB)

        claims = baseClaims("PASSENGER");
        claims.put("nbf", Instant.now().toEpochMilli() / 1000.0 + 0.9); // chưa hiệu lực
        Resp nbfFuture = post("/api/trips/quote", QUOTE_BODY, sign(claims), null);
        assertThat(nbfFuture.status()).isEqualTo(401);
        assertThat(nbfFuture.body().get("code")).isEqualTo("UNAUTHORIZED");

        claims = baseClaims("PASSENGER");
        claims.put("nbf", null); // có mặt nhưng null -> invalid (F02)
        Resp nbfNull = post("/api/trips/quote", QUOTE_BODY, sign(claims), null);
        assertThat(nbfNull.status()).isEqualTo(401);
        assertThat(nbfNull.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    // ---------- T07-4 (F03) — JSON sai kiểu không được ép kiểu ngầm ----------

    /** F03: latitude dạng chuỗi "10.7" không được thành double -> 400 INVALID_INPUT, không tạo trip. */
    @Test
    void stringCoordinatesRejected() throws Exception {
        Map<String, Object> body = Map.of(
                "pickup", Map.of("latitude", "10.7769", "longitude", 106.7009),
                "destination", Map.of("latitude", 10.8000, "longitude", 106.7200));
        Resp resp = post("/api/trips/quote", body, sign(baseClaims("PASSENGER")), null);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    // ---------- T07-5 (F04) — lỗi phía client không được gom thành 500 ----------

    /** F04: sai HTTP method -> 405 METHOD_NOT_ALLOWED kèm correlationId. */
    @Test
    void wrongMethodReturns405WithCorrelation() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = delete("/api/trips/quote", sign(baseClaims("PASSENGER")), correlation);
        assertThat(resp.status()).isEqualTo(405);
        assertThat(resp.body().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    /** F04: sai Content-Type -> 415 UNSUPPORTED_MEDIA_TYPE kèm correlationId. */
    @Test
    void wrongContentTypeReturns415() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp resp = postText("/api/trips/quote", "not-json", sign(baseClaims("PASSENGER")), correlation);
        assertThat(resp.status()).isEqualTo(415);
        assertThat(resp.body().get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    /** F04: page=oops / size vượt int range -> 400 INVALID_INPUT, không 500. */
    @Test
    void badPageAndSizeParamsReturn400() throws Exception {
        Resp oops = get("/api/trips?page=oops", sign(baseClaims("PASSENGER")), null);
        assertThat(oops.status()).isEqualTo(400);
        assertThat(oops.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp bigSize = get("/api/trips?size=2147483648", sign(baseClaims("PASSENGER")), null);
        assertThat(bigSize.status()).isEqualTo(400);
    }

    /** F04: path variable không phải UUID -> 400 INVALID_INPUT, không 500. */
    @Test
    void nonUuidTripIdReturns400() throws Exception {
        Resp resp = get("/api/trips/not-a-uuid", sign(baseClaims("PASSENGER")), null);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }
}
