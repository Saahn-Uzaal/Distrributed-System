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
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18099",
                // Flyway không auto-configure trong Boot 4; bean custom FlywayConfig
                // tắt bằng app.flyway.enabled=false.
                "spring.datasource.url=jdbc:h2:mem:trip_probe;DB_CLOSE_DELAY=-1",
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
    private static final String BASE = "http://localhost:18099";

    record Resp(int status, Map<String, Object> body) {
    }

    private Resp get(String path, String bearerToken, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
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
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path))
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
}
