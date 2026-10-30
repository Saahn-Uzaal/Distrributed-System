package com.ridehailing.driver;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.ridehailing.driver.geo.Haversine;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09 — integration test driver profile/availability/location/candidates trên HTTP thật +
 * PostgreSQL thật (Testcontainers, cần Docker). Flyway chạy V1+V2 lên DB container.
 * JWT ký bằng private key test (public key inject qua property) — pattern SecurityProbeTest.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DriverProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("driver_test")
            .withPassword("driver_test_pw");

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;
    private static final String INTERNAL_TOKEN = "test-internal-token-t09";

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-driver-t09");
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
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    // --- helpers HTTP/JWT/DB ---

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
        return send(method, path, bearerToken, internalToken, null, jsonBody);
    }

    private Resp send(String method, String path, String bearerToken, String internalToken, String correlationHeader, String jsonBody) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path));
        switch (method) {
            case "GET" -> req.GET();
            case "POST" -> req.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            case "PUT" -> req.PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            default -> throw new IllegalArgumentException(method);
        }
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        if (internalToken != null) {
            req.header("X-Internal-Token", internalToken);
        }
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
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

    /** Seed driver row trực tiếp vào DB (kiểm soát state/heartbeat cho candidates tests). */
    private UUID seedDriver(UUID userId, String status, Instant lastSeenAt, Double lat, Double lng) {
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'CAR') RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                "T9-" + userId);
        return jdbc.queryForObject(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status, latitude, longitude, last_seen_at) "
                        + "VALUES (?, 'Seed Driver', ?, ?, ?, ?, ?) RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                userId, vehicleId, status, lat, lng, Timestamp.from(lastSeenAt));
    }

    /** pgjdbc có thể trả UUID hoặc String cho cột uuid — nhận cả hai. */
    private static UUID toUuid(Object id) {
        if (id instanceof UUID u) {
            return u;
        }
        return UUID.fromString(id.toString());
    }

    private UUID createProfileViaApi(UUID userId, String displayName, String plate, String type) throws Exception {
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(userId, "DRIVER", userId + "@t9.test"), null,
                "{\"displayName\":\"" + displayName + "\",\"vehicle\":{\"plateNumber\":\"" + plate + "\",\"vehicleType\":\"" + type + "\"}}");
        assertThat(resp.status()).as("profile create status").isEqualTo(201);
        return UUID.fromString((String) resp.body().get("driverId"));
    }

    // --- profile ---

    @org.junit.jupiter.api.Test
    void profileFirstCallReturns201() throws Exception {
        UUID user = UUID.randomUUID();
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(user, "DRIVER", user + "@t9.test"), null,
                "{\"displayName\":\"Driver A\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"MOTORBIKE\"}}");
        assertThat(resp.status()).isEqualTo(201);
        Map<String, Object> body = resp.body();
        UUID.fromString((String) body.get("driverId")); // parse hợp lệ
        assertThat(body.get("displayName")).isEqualTo("Driver A");
        @SuppressWarnings("unchecked")
        Map<String, Object> vehicle = (Map<String, Object>) body.get("vehicle");
        assertThat(vehicle.get("plateNumber")).isEqualTo("51A-123.45");
        assertThat(vehicle.get("vehicleType")).isEqualTo("MOTORBIKE");
        assertThat(body.get("status")).isEqualTo("OFFLINE");
    }

    @org.junit.jupiter.api.Test
    void profileRepeatReturns200WithoutUpdate() throws Exception {
        UUID user = UUID.randomUUID();
        createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(user, "DRIVER", user + "@t9.test"), null,
                "{\"displayName\":\"Driver B\",\"vehicle\":{\"plateNumber\":\"99B-999.99\",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(200);
        // Idempotent theo userId: trả profile gốc, KHÔNG cập nhật body mới (chốt §3.2).
        assertThat(resp.body().get("displayName")).isEqualTo("Driver A");
        @SuppressWarnings("unchecked")
        Map<String, Object> vehicle = (Map<String, Object>) resp.body().get("vehicle");
        assertThat(vehicle.get("plateNumber")).isEqualTo("51A-123.45");
    }

    @org.junit.jupiter.api.Test
    void profileValidationRejectsInvalidInput() throws Exception {
        UUID user = UUID.randomUUID();
        String token = signToken(user, "DRIVER", user + "@t9.test");
        // displayName < 2 / > 60
        assertThat(send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"A\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}").status()).isEqualTo(400);
        String longName = "a".repeat(61);
        Resp r = send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"" + longName + "\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code")).isEqualTo("INVALID_INPUT");
        // plate < 3 / > 20
        assertThat(send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"Driver A\",\"vehicle\":{\"plateNumber\":\"ab\",\"vehicleType\":\"CAR\"}}").status()).isEqualTo(400);
        String longPlate = "a".repeat(21);
        assertThat(send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"Driver A\",\"vehicle\":{\"plateNumber\":\"" + longPlate + "\",\"vehicleType\":\"CAR\"}}").status()).isEqualTo(400);
        // vehicleType ngoài allowlist / thiếu vehicle object
        assertThat(send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"Driver A\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"TRUCK\"}}").status()).isEqualTo(400);
        assertThat(send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"Driver A\"}").status()).isEqualTo(400);
    }

    /** N03 (T07-11): body > 64 KiB -> 413 PAYLOAD_TOO_LARGE JSON, correlationId echo. */
    @org.junit.jupiter.api.Test
    void oversizedBodyReturns413PayloadTooLarge() throws Exception {
        UUID user = UUID.randomUUID();
        String token = signToken(user, "DRIVER", user + "@t9.test");
        String correlation = UUID.randomUUID().toString();
        Resp resp = send("POST", "/api/drivers/me/profile", token, null, correlation,
                "{\"displayName\":\"" + "a".repeat(70_000) + "\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(413);
        assertThat(resp.body().get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
    }

    /** F05 (T06-1): displayName chứa ký tự điều khiển \u0000 → 400 INVALID_INPUT, không ghi row. */
    @org.junit.jupiter.api.Test
    void controlCharInDisplayNameRejectedWithoutPersisting() throws Exception {
        UUID user = UUID.randomUUID();
        String token = signToken(user, "DRIVER", user + "@t9.test");
        Resp resp = send("POST", "/api/drivers/me/profile", token, null,
                "{\"displayName\":\"ab\\u0000c\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drivers WHERE user_id = ?", Integer.class, user);
        assertThat(count).isZero();
    }

    @org.junit.jupiter.api.Test
    void duplicatePlateReturns409PlateTaken() throws Exception {
        UUID user1 = UUID.randomUUID();
        createProfileViaApi(user1, "Driver A", "51A-123.45", "MOTORBIKE");
        UUID user2 = UUID.randomUUID();
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(user2, "DRIVER", user2 + "@t9.test"), null,
                "{\"displayName\":\"Driver C\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body().get("code")).isEqualTo("PLATE_TAKEN");
        // Không ghi driver row cho user2 (transaction rollback).
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drivers WHERE user_id = ?", Integer.class, user2);
        assertThat(count).isZero();
    }

    @org.junit.jupiter.api.Test
    void passengerRoleForbiddenOnProfile() throws Exception {
        UUID user = UUID.randomUUID();
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(user, "PASSENGER", user + "@t9.test"), null,
                "{\"displayName\":\"Driver A\",\"vehicle\":{\"plateNumber\":\"51A-123.45\",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.body().get("code")).isEqualTo("FORBIDDEN");
    }

    // --- availability ---

    @org.junit.jupiter.api.Test
    void availabilityTransitionsAndHeartbeat() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");

        Resp on = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"AVAILABLE\"}");
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.body().get("driverId")).isEqualTo(driverId.toString());
        assertThat(on.body().get("status")).isEqualTo("AVAILABLE");
        String lastSeen = (String) on.body().get("lastSeenAt");
        assertThat(lastSeen).isNotNull().endsWith("Z");

        Integer statusInDb = jdbc.queryForObject(
                "SELECT CASE status WHEN 'AVAILABLE' THEN 1 ELSE 0 END FROM drivers WHERE id = ?", Integer.class, driverId);
        assertThat(statusInDb).isEqualTo(1);
        Instant dbLastSeen = jdbc.queryForObject("SELECT last_seen_at FROM drivers WHERE id = ?", Instant.class, driverId);
        assertThat(dbLastSeen).isNotNull();

        Resp off = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"OFFLINE\"}");
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body().get("status")).isEqualTo("OFFLINE");

        // status ngoài {AVAILABLE, OFFLINE} -> 400 (chốt T09)
        Resp bad = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"BUSY\"}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    @org.junit.jupiter.api.Test
    void availabilityConflictWhenReservedOrBusy() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");
        UUID tripId = UUID.randomUUID();
        jdbc.update("UPDATE drivers SET status = 'RESERVED', current_trip_id = ? WHERE id = ?", tripId, driverId);

        Resp off = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"OFFLINE\"}");
        assertThat(off.status()).isEqualTo(409);
        assertThat(off.body().get("code")).isEqualTo("STATE_CONFLICT");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) off.body().get("details");
        assertThat(details.get("state")).isEqualTo("RESERVED");
        assertThat(details.get("tripId")).isEqualTo(tripId.toString());

        // Chốt T09: AVAILABLE khi RESERVED cũng 409 (không có transition PUT từ RESERVED).
        Resp on = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"AVAILABLE\"}");
        assertThat(on.status()).isEqualTo(409);
    }

    /** T09-3 (F13): driver RESERVED với current_trip_id NULL -> 409 details.tripId lấy từ reservation. */
    @org.junit.jupiter.api.Test
    void offlineConflictResolvesTripIdFromReservationWhenCurrentTripIsNull() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");
        UUID reservationId = UUID.randomUUID(), tripId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservations (id, trip_id, driver_id, attempt, state, expires_at, last_command_version,"
                        + " pickup_lat, pickup_lng, destination_lat, destination_lng, distance_m, fare_vnd)"
                        + " VALUES (?, ?, ?, 1, 'RESERVED', now() + interval '30 seconds', 1, 10.7, 106.7, 10.8, 106.8, 1500, 45000)",
                reservationId, tripId, driverId);
        jdbc.update("UPDATE drivers SET status = 'RESERVED', current_reservation_id = ? WHERE id = ?", reservationId, driverId);

        Resp off = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"OFFLINE\"}");
        assertThat(off.status()).isEqualTo(409);
        assertThat(off.body().get("code")).isEqualTo("STATE_CONFLICT");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) off.body().get("details");
        assertThat(details.get("state")).isEqualTo("RESERVED");
        assertThat(details.get("tripId")).isEqualTo(tripId.toString());
    }

    /** T09-4 (F14): plate normalize (trim/uppercase) -> "51a-123.45 " trùng "51A-123.45" -> 409 PLATE_TAKEN; giá trị lưu đã chuẩn hóa. */
    @org.junit.jupiter.api.Test
    void plateNormalizationMakesCaseAndWhitespaceDuplicatesConflict() throws Exception {
        UUID user1 = UUID.randomUUID();
        createProfileViaApi(user1, "Driver A", "51A-123.45", "MOTORBIKE");
        String storedPlate = jdbc.queryForObject(
                "SELECT plate_number FROM vehicles WHERE id = (SELECT vehicle_id FROM drivers WHERE user_id = ?)",
                String.class, user1);
        assertThat(storedPlate).isEqualTo("51A-123.45");

        UUID user2 = UUID.randomUUID();
        Resp resp = send("POST", "/api/drivers/me/profile", signToken(user2, "DRIVER", user2 + "@t9.test"), null,
                "{\"displayName\":\"Driver C\",\"vehicle\":{\"plateNumber\":\"51a-123.45 \",\"vehicleType\":\"CAR\"}}");
        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body().get("code")).isEqualTo("PLATE_TAKEN");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drivers WHERE user_id = ?", Integer.class, user2);
        assertThat(count).isZero();
    }

    @org.junit.jupiter.api.Test
    void availabilityAndLocationRequireProfile() throws Exception {
        UUID user = UUID.randomUUID(); // chưa tạo profile
        String token = signToken(user, "DRIVER", user + "@t9.test");
        Resp avail = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"AVAILABLE\"}");
        assertThat(avail.status()).isEqualTo(404);
        assertThat(avail.body().get("code")).isEqualTo("NOT_FOUND");
        Resp loc = send("PUT", "/api/drivers/me/location", token, null,
                "{\"latitude\":10.7769,\"longitude\":106.7009}");
        assertThat(loc.status()).isEqualTo(404);
    }

    // --- GET /api/drivers/me ---

    @org.junit.jupiter.api.Test
    void getMeReturnsServerStateWithoutTouchingHeartbeat() throws Exception {
        UUID user = UUID.randomUUID();
        Instant seen = Instant.parse("2026-10-20T01:02:03Z");
        UUID driverId = seedDriver(user, "OFFLINE", seen, 21.03, 105.84);
        String token = signToken(user, "DRIVER", user + "@t9.test");

        Resp me = send("GET", "/api/drivers/me", token, null, null);

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("driverId")).isEqualTo(driverId.toString());
        assertThat(me.body().get("displayName")).isEqualTo("Seed Driver");
        assertThat(me.body().get("status")).isEqualTo("OFFLINE");
        assertThat(me.body().get("latitude")).isEqualTo(21.03);
        assertThat(me.body().get("longitude")).isEqualTo(105.84);
        assertThat(me.body()).containsKey("tripId");
        assertThat(me.body().get("tripId")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> vehicle = (Map<String, Object>) me.body().get("vehicle");
        assertThat(vehicle.get("vehicleType")).isEqualTo("CAR");

        // Chỉ đọc: tải lại trang không được đổi OFFLINE thành AVAILABLE hay làm mới heartbeat.
        Map<String, Object> row = jdbc.queryForMap("SELECT status, last_seen_at FROM drivers WHERE id = ?", driverId);
        assertThat(row.get("status")).isEqualTo("OFFLINE");
        assertThat(((Timestamp) row.get("last_seen_at")).toInstant()).isEqualTo(seen);
    }

    @org.junit.jupiter.api.Test
    void getMeExposesRelatedTripForReservedAndBusy() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");
        UUID reservationId = UUID.randomUUID(), tripId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO reservations (id, trip_id, driver_id, attempt, state, expires_at, last_command_version,"
                        + " pickup_lat, pickup_lng, destination_lat, destination_lng, distance_m, fare_vnd)"
                        + " VALUES (?, ?, ?, 1, 'RESERVED', now() + interval '30 seconds', 1, 10.7, 106.7, 10.8, 106.8, 1500, 45000)",
                reservationId, tripId, driverId);
        jdbc.update("UPDATE drivers SET status = 'RESERVED', current_reservation_id = ? WHERE id = ?", reservationId, driverId);

        Resp reserved = send("GET", "/api/drivers/me", token, null, null);
        assertThat(reserved.body().get("status")).isEqualTo("RESERVED");
        assertThat(reserved.body().get("tripId")).isEqualTo(tripId.toString());

        jdbc.update("UPDATE drivers SET status = 'BUSY', current_trip_id = ? WHERE id = ?", tripId, driverId);
        Resp busy = send("GET", "/api/drivers/me", token, null, null);
        assertThat(busy.body().get("status")).isEqualTo("BUSY");
        assertThat(busy.body().get("tripId")).isEqualTo(tripId.toString());
    }

    @org.junit.jupiter.api.Test
    void getMeRequiresProfileAndDriverRole() throws Exception {
        UUID user = UUID.randomUUID();
        Resp noProfile = send("GET", "/api/drivers/me", signToken(user, "DRIVER", user + "@t9.test"), null, null);
        assertThat(noProfile.status()).isEqualTo(404);
        assertThat(noProfile.body().get("code")).isEqualTo("NOT_FOUND");

        Resp passenger = send("GET", "/api/drivers/me", signToken(user, "PASSENGER", user + "@t9.test"), null, null);
        assertThat(passenger.status()).isEqualTo(403);
        assertThat(send("GET", "/api/drivers/me", null, null, null).status()).isEqualTo(401);
    }

    // --- location ---

    @org.junit.jupiter.api.Test
    void locationUpdatesCoordinatesAndHeartbeat() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");

        Resp resp = send("PUT", "/api/drivers/me/location", token, null,
                "{\"latitude\":10.7769,\"longitude\":106.7009}");
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().get("driverId")).isEqualTo(driverId.toString());
        String lastSeen = (String) resp.body().get("lastSeenAt");
        assertThat(lastSeen).isNotNull().endsWith("Z");

        Double lat = jdbc.queryForObject("SELECT latitude FROM drivers WHERE id = ?", Double.class, driverId);
        Double lng = jdbc.queryForObject("SELECT longitude FROM drivers WHERE id = ?", Double.class, driverId);
        assertThat(lat).isEqualTo(10.7769);
        assertThat(lng).isEqualTo(106.7009);

        // Cho phép gọi ở mọi state (kể cả BUSY) — chốt §3.2.
        jdbc.update("UPDATE drivers SET status = 'BUSY' WHERE id = ?", driverId);
        Resp busy = send("PUT", "/api/drivers/me/location", token, null,
                "{\"latitude\":10.8,\"longitude\":106.7}");
        assertThat(busy.status()).isEqualTo(200);
    }

    @org.junit.jupiter.api.Test
    void locationRejectsInvalidCoordinates() throws Exception {
        UUID user = UUID.randomUUID();
        createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");

        Resp latOut = send("PUT", "/api/drivers/me/location", token, null, "{\"latitude\":91,\"longitude\":106.7}");
        assertThat(latOut.status()).isEqualTo(400);
        assertThat(latOut.body().get("code")).isEqualTo("INVALID_INPUT");
        Resp lngOut = send("PUT", "/api/drivers/me/location", token, null, "{\"latitude\":10.7,\"longitude\":-181}");
        assertThat(lngOut.status()).isEqualTo(400);
        Resp missing = send("PUT", "/api/drivers/me/location", token, null, "{\"latitude\":10.7}");
        assertThat(missing.status()).isEqualTo(400);
        Resp notNumber = send("PUT", "/api/drivers/me/location", token, null,
                "{\"latitude\":\"abc\",\"longitude\":106.7}");
        assertThat(notNumber.status()).isEqualTo(400);
    }

    // --- candidates (internal) ---

    private static final double Q_LAT = 10.7769;
    private static final double Q_LNG = 106.7009;

    @org.junit.jupiter.api.Test
    void candidatesExcludeOfflineStaleAndFar() throws Exception {
        Instant now = Instant.now();
        UUID nearFresh = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7814, Q_LNG);          // ~500m
        seedDriver(UUID.randomUUID(), "AVAILABLE", now.minusSeconds(60), 10.7814, Q_LNG);         // stale heartbeat
        seedDriver(UUID.randomUUID(), "OFFLINE", now, 10.7770, 106.7010);                        // OFFLINE gần
        seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.8769, Q_LNG);                         // ~11km

        Resp resp = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG, null, INTERNAL_TOKEN, null);
        assertThat(resp.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.body().get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(UUID.fromString((String) candidates.get(0).get("driverId"))).isEqualTo(nearFresh);
    }

    @org.junit.jupiter.api.Test
    void candidatesDeterministicOrderAndTieBreak() throws Exception {
        Instant now = Instant.now();
        // 3 driver ở khoảng cách khác nhau + 2 driver cùng tọa độ (tie distanceM).
        UUID d500 = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7814, Q_LNG);   // ~500m
        UUID d1k = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7859, Q_LNG);    // ~1km
        UUID d2k = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7949, Q_LNG);    // ~2km
        UUID tieA = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.8014, Q_LNG);   // ~2.75km
        UUID tieB = seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.8014, Q_LNG);   // cùng tọa độ tieA

        Resp resp = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG, null, INTERNAL_TOKEN, null);
        assertThat(resp.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.body().get("candidates");

        // Kỳ vọng: distanceM tăng; tie-break driverId lexicographic — tính bằng cùng công thức Haversine.
        record Exp(UUID id, long dist) {
        }
        List<Exp> expected = new ArrayList<>(List.of(
                new Exp(d500, Haversine.meters(Q_LAT, Q_LNG, 10.7814, Q_LNG)),
                new Exp(d1k, Haversine.meters(Q_LAT, Q_LNG, 10.7859, Q_LNG)),
                new Exp(d2k, Haversine.meters(Q_LAT, Q_LNG, 10.7949, Q_LNG)),
                new Exp(tieA, Haversine.meters(Q_LAT, Q_LNG, 10.8014, Q_LNG)),
                new Exp(tieB, Haversine.meters(Q_LAT, Q_LNG, 10.8014, Q_LNG))));
        expected.sort(Comparator.comparingLong(Exp::dist).thenComparing(e -> e.id().toString()));

        assertThat(candidates).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            assertThat(UUID.fromString((String) c.get("driverId"))).isEqualTo(expected.get(i).id());
            assertThat(((Number) c.get("distanceM")).longValue()).isEqualTo(expected.get(i).dist());
        }
    }

    @org.junit.jupiter.api.Test
    void candidatesClampsLimitAndValidatesParams() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7814 + i * 0.0001, Q_LNG);
        }

        // limit > 5 clamp về 5 (ở đây chỉ có 3 -> trả 3).
        Resp big = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG + "&limit=999", null, INTERNAL_TOKEN, null);
        assertThat(big.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) big.body().get("candidates");
        assertThat(list).hasSizeLessThanOrEqualTo(5);

        // radiusM <= 0 -> 400; limit < 1 -> 400; thiếu lat -> 400.
        Resp badRadius = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG + "&radiusM=-100", null, INTERNAL_TOKEN, null);
        assertThat(badRadius.status()).isEqualTo(400);
        assertThat(badRadius.body().get("code")).isEqualTo("INVALID_INPUT");
        Resp badLimit = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG + "&limit=0", null, INTERNAL_TOKEN, null);
        assertThat(badLimit.status()).isEqualTo(400);
        Resp missingLat = send("GET", "/internal/drivers/candidates?lng=" + Q_LNG, null, INTERNAL_TOKEN, null);
        assertThat(missingLat.status()).isEqualTo(400);
    }

    @org.junit.jupiter.api.Test
    void candidatesRequireInternalCredential() throws Exception {
        Instant now = Instant.now();
        seedDriver(UUID.randomUUID(), "AVAILABLE", now, 10.7814, Q_LNG);
        Resp resp = send("GET", "/internal/drivers/candidates?lat=" + Q_LAT + "&lng=" + Q_LNG, null, null, null);
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
    }
    @org.junit.jupiter.api.Test
    void reviewConcurrentProfileRetryMustBeIdempotent() throws Exception {
        UUID user=UUID.randomUUID();
        String token=signToken(user,"DRIVER","review@example.test");
        String body="{\"displayName\":\"Review Driver\",\"vehicle\":{\"plateNumber\":\"REVIEW-123\",\"vehicleType\":\"CAR\"}}";
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Resp>> futures=new java.util.ArrayList<>();
        try(var lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE vehicles IN SHARE MODE");
            for(int i=0;i<2;i++) futures.add(pool.submit(()->send("POST","/api/drivers/me/profile",token,null,body)));
            int waiting=0;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE 'INSERT INTO vehicles%' OR query LIKE '%pg_advisory_xact_lock%')",Integer.class);
                if(waiting==2) break;
                Thread.sleep(20);
            }
            lock.commit();
            assertThat(waiting).as("both requests passed profile lookup before insert").isEqualTo(2);
            Resp a=futures.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);
            Resp b=futures.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("REVIEW concurrent profile: "+a.status()+"/"+a.body().get("code")+", "+b.status()+"/"+b.body().get("code"));
            assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(201,200);
            assertThat(a.body()).isEqualTo(b.body());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drivers",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vehicles",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }


    @org.junit.jupiter.api.Test
    void candidatesCarryDistinctDriverProfileAndIdentityUserIds() throws Exception {
        UUID user=UUID.randomUUID();
        UUID driver=seedDriver(user,"AVAILABLE",Instant.now(),10.7769,106.7009);
        assertThat(driver).isNotEqualTo(user);
        Resp response=send("GET","/internal/drivers/candidates?lat=10.7769&lng=106.7009",null,INTERNAL_TOKEN,null);
        assertThat(response.status()).isEqualTo(200);
        var candidates=(List<?>)response.body().get("candidates");
        assertThat(candidates).hasSize(1);
        var candidate=(Map<?,?>)candidates.get(0);
        assertThat(candidate.get("driverId")).isEqualTo(driver.toString());
        assertThat(candidate.get("driverUserId")).isEqualTo(user.toString());
    }

    /** N11 — audit log driver_availability_changed: structured, không ghi secret. */
    @org.junit.jupiter.api.Test
    void availabilityChangeAuditLogIsStructuredAndSecretFree() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.ridehailing.driver.service.DriverService");
        com.ridehailing.driver.logging.JsonLogLayout layout = new com.ridehailing.driver.logging.JsonLogLayout();
        layout.setService("driver-service");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.setName("AUDIT_PROBE");
        appender.setOutputStream(out);
        appender.setLayout(layout);
        appender.start();

        UUID user = UUID.randomUUID();
        createProfileViaApi(user, "Driver A", "51A-123.45", "MOTORBIKE");
        String token = signToken(user, "DRIVER", user + "@t9.test");
        logger.addAppender(appender);
        try {
            Resp on = send("PUT", "/api/drivers/me/availability", token, null, "{\"status\":\"AVAILABLE\"}");
            assertThat(on.status()).isEqualTo(200);

            String text = out.toString(StandardCharsets.UTF_8);
            Map<String, Object> audit = null;
            for (String line : Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.readValue(line.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
                if ("driver_availability_changed".equals(m.get("eventType"))) {
                    audit = m;
                    break;
                }
            }
            assertThat(audit).isNotNull();
            assertThat(audit.get("result")).isEqualTo("SUCCESS");
            // Không secret: không Bearer token, không email.
            assertThat(text).doesNotContain("Bearer ").doesNotContain("@t9.test");
        } finally {
            logger.detachAndStopAllAppenders();
        }
    }

}
