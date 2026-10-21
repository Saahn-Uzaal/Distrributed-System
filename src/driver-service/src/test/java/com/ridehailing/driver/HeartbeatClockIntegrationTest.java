package com.ridehailing.driver;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09-1 (F07) + T10-1 (F06) — nguồn đồng hồ duy nhất là clock_timestamp() của PostgreSQL.
 * Threshold heartbeat ngắn (500 ms) để kiểm chứng:
 *  - T09-1: request location chờ row lock 750 ms -> last_seen_at được gán bằng DB clock lúc commit
 *    (fresh), driver vẫn xuất hiện trong candidates ngay sau đó.
 *  - T10-1: reserve chờ row lock vượt threshold -> CAS heartbeat đánh giá bằng clock_timestamp()
 *    SAU khi có khóa -> stale -> 409 DRIVER_NOT_AVAILABLE (không phải fresh theo JVM clock cũ).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "driver.heartbeat-stale-after-ms=500")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class HeartbeatClockIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("heartbeat_test")
            .withPassword("heartbeat_test_pw");

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;
    private static final String INTERNAL_TOKEN = "test-internal-token-hb";

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-heartbeat");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = KEYS_DIR.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
            SIGNER = new RSASSASigner(pair.getPrivate());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("internal.api-token", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    record Resp(int status, Map<String, Object> body) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM processed_commands");
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    private String signToken(UUID sub, String role, String email) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of("driver-service"));
        claims.put("sub", sub.toString());
        claims.put("role", role);
        claims.put("email", email);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jws.sign(SIGNER);
        return jws.serialize();
    }

    private Resp send(String method, String path, String bearerToken, String internalToken, String jsonBody) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path));
        switch (method) {
            case "GET" -> req.GET();
            case "PUT" -> req.PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            default -> throw new IllegalArgumentException(method);
        }
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        if (internalToken != null) {
            req.header("X-Internal-Token", internalToken);
        }
        req.header("Content-Type", "application/json");
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

    private UUID seedDriver(UUID userId, String status, Instant lastSeenAt, Double lat, Double lng) {
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'CAR') RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                "HB-" + userId);
        return jdbc.queryForObject(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status, latitude, longitude, last_seen_at)"
                        + " VALUES (?, 'Seed Driver', ?, ?, ?, ?, ?) RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                userId, vehicleId, status, lat, lng, Timestamp.from(lastSeenAt));
    }

    private static UUID toUuid(Object id) {
        if (id instanceof UUID u) {
            return u;
        }
        return UUID.fromString(id.toString());
    }

    /** Giữ row lock của driver bằng raw JDBC trong holdMs rồi thả. */
    private void holdRowLockFor(UUID driverId, long holdMs) throws Exception {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT id FROM drivers WHERE id='" + driverId + "' FOR UPDATE");
                Thread.sleep(holdMs);
            }
            conn.commit();
        }
    }

    /** T09-1: location chờ lock 750 ms -> last_seen_at fresh theo DB clock, vẫn vào candidates. */
    @org.junit.jupiter.api.Test
    void locationWaitingOnRowLockGetsFreshDbClockTimestamp() throws Exception {
        UUID user = UUID.randomUUID();
        Instant seededAt = Instant.now();
        UUID driverId = seedDriver(user, "AVAILABLE", seededAt, 10.7769, 106.7009);

        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var hold = pool.submit(() -> {
                try {
                    holdRowLockFor(driverId, 750);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            // Chờ lock thật sự được giữ trước khi gọi location.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Integer waiting = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM drivers%'",
                        Integer.class);
                if (waiting > 0) break;
                Thread.sleep(10);
            }

            Resp resp = send("PUT", "/api/drivers/me/location", signToken(user, "DRIVER", user + "@hb.test"), null,
                    "{\"latitude\":10.78,\"longitude\":106.71}");
            assertThat(resp.status()).isEqualTo(200);
            hold.get(15, java.util.concurrent.TimeUnit.SECONDS);

            // last_seen_at phải là DB clock lúc commit (>= seededAt + ~700 ms), không phải thời điểm request.
            Instant dbLastSeen = jdbc.queryForObject("SELECT last_seen_at FROM drivers WHERE id=?", Instant.class, driverId);
            assertThat(dbLastSeen).isNotNull();
            assertThat(dbLastSeen.isAfter(seededAt.plusMillis(600))).isTrue();

            // Fresh theo threshold 500 ms -> xuất hiện trong candidates ngay sau đó.
            Resp cand = send("GET", "/internal/drivers/candidates?lat=10.7769&lng=106.7009", null, INTERNAL_TOKEN, null);
            assertThat(cand.status()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) cand.body().get("candidates");
            assertThat(candidates).hasSize(1);
            assertThat(UUID.fromString((String) candidates.get(0).get("driverId"))).isEqualTo(driverId);
        } finally {
            pool.shutdownNow();
        }
    }

    /** T10-1: reserve chờ lock vượt threshold -> heartbeat stale theo DB clock -> 409. */
    @org.junit.jupiter.api.Test
    void reserveWaitingOnRowLockSeesStaleHeartbeatByDbClock() throws Exception {
        UUID user = UUID.randomUUID();
        Instant seededAt = Instant.now();
        UUID driverId = seedDriver(user, "AVAILABLE", seededAt, 10.7769, 106.7009);

        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var hold = pool.submit(() -> {
                try {
                    holdRowLockFor(driverId, 1200);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Integer waiting = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT id FROM drivers%'",
                        Integer.class);
                if (waiting > 0) break;
                Thread.sleep(10);
            }

            Resp resp = send("PUT", "/internal/reservations/" + UUID.randomUUID(), null, INTERNAL_TOKEN,
                    "{\"commandId\":\"" + UUID.randomUUID() + "\","
                            + "\"commandVersion\":1,"
                            + "\"tripId\":\"" + UUID.randomUUID() + "\","
                            + "\"driverId\":\"" + driverId + "\","
                            + "\"attempt\":1,"
                            + "\"expiresAt\":\"" + Instant.now().plusSeconds(30).toString() + "\","
                            + "\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                            + "\"destination\":{\"latitude\":10.8000,\"longitude\":10.7200},"
                            + "\"distanceM\":3316,"
                            + "\"fareVnd\":32000}");
            hold.get(15, java.util.concurrent.TimeUnit.SECONDS);

            // CAS heartbeat đánh giá bằng clock_timestamp() SAU khi chờ lock 1200 ms > threshold 500 ms.
            assertThat(resp.status()).isEqualTo(409);
            assertThat(resp.body().get("code")).isEqualTo("DRIVER_NOT_AVAILABLE");
            String status = jdbc.queryForObject("SELECT status FROM drivers WHERE id=?", String.class, driverId);
            assertThat(status).isEqualTo("AVAILABLE");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class);
            assertThat(count).isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
