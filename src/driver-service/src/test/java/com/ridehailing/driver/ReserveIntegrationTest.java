package com.ridehailing.driver;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10 — integration test giữ chỗ nguyên tử trên HTTP thật + PostgreSQL thật (Testcontainers, cần Docker).
 * Flyway chạy V1+V2+V3 lên DB container. Acceptance T10:
 *  - hai reservation cạnh tranh một driver chỉ một thành công;
 *  - retry cùng command không tạo thêm record;
 *  - payload khác cùng commandId trả 409 COMMAND_CONFLICT;
 *  + STALE_VERSION, DRIVER_NOT_AVAILABLE (OFFLINE/heartbeat stale), TOMBSTONED_RESERVATION.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18100")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ReserveIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("driver_test")
            .withPassword("driver_test_pw");

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-driver-t10");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = KEYS_DIR.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String INTERNAL_TOKEN = "test-internal-token-t10";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Internal endpoints không cần JWT, nhưng bean JwtVerifier vẫn khởi tạo — dùng public key test.
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("internal.api-token", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE = "http://localhost:18100";

    record Resp(int status, Map<String, Object> body) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM processed_commands");
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    // --- helpers HTTP/DB ---

    private Resp send(String method, String path, String internalToken, String jsonBody) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path));
        switch (method) {
            case "GET" -> req.GET();
            case "PUT" -> req.PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            default -> throw new IllegalArgumentException(method);
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

    /** Seed driver row trực tiếp vào DB (kiểm soát state/heartbeat cho các nhánh lỗi). */
    private UUID seedDriver(UUID userId, String status, Instant lastSeenAt, Double lat, Double lng) {
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'CAR') RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                "T10-" + userId);
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

    /** Body reserve theo contract §4.2; fareVnd thay đổi được để test COMMAND_CONFLICT. */
    private String reserveJson(UUID commandId, long version, UUID tripId, UUID driverId, int attempt,
                              Instant expiresAt, long fareVnd) {
        return "{\"commandId\":\"" + commandId + "\","
                + "\"commandVersion\":" + version + ","
                + "\"tripId\":\"" + tripId + "\","
                + "\"driverId\":\"" + driverId + "\","
                + "\"attempt\":" + attempt + ","
                + "\"expiresAt\":\"" + expiresAt.toString() + "\","
                + "\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                + "\"destination\":{\"latitude\":10.8000,\"longitude\":10.7200},"
                + "\"distanceM\":3316,"
                + "\"fareVnd\":" + fareVnd + "}";
    }

    private Resp reserve(UUID reservationId, UUID commandId, long version, UUID tripId, UUID driverId,
                         int attempt, Instant expiresAt, long fareVnd) throws Exception {
        return send("PUT", "/internal/reservations/" + reservationId, INTERNAL_TOKEN,
                reserveJson(commandId, version, tripId, driverId, attempt, expiresAt, fareVnd));
    }

    private int countReservations() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class);
        return c == null ? 0 : c;
    }

    private int countProcessed(UUID commandId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_commands WHERE command_id = ?", Integer.class, commandId);
        return c == null ? 0 : c;
    }

    // --- tests ---

    @org.junit.jupiter.api.Test
    void reserveSuccessReturns201AndDriverReserved() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        Resp resp = reserve(reservationId, UUID.randomUUID(), 1, tripId, driverId, 1,
                Instant.now().plusSeconds(30), 32000);
        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.body().get("reservationId")).isEqualTo(reservationId.toString());
        assertThat(resp.body().get("state")).isEqualTo("RESERVED");
        assertThat(((Number) resp.body().get("version")).intValue()).isEqualTo(1);

        // Driver CAS: RESERVED + current_reservation_id set (invariant 1).
        Map<String, Object> driver = jdbc.queryForMap(
                "SELECT status, current_reservation_id FROM drivers WHERE id = ?", driverId);
        assertThat(driver.get("status")).isEqualTo("RESERVED");
        assertThat(toUuid(driver.get("current_reservation_id"))).isEqualTo(reservationId);

        // Reservation row lưu sẵn thông tin chuyến (cho GET /drivers/me/offer — T17).
        Map<String, Object> res = jdbc.queryForMap(
                "SELECT state, last_command_version, distance_m, fare_vnd FROM reservations WHERE id = ?",
                reservationId);
        assertThat(res.get("state")).isEqualTo("RESERVED");
        assertThat(((Number) res.get("last_command_version")).intValue()).isEqualTo(1);
        assertThat(((Number) res.get("distance_m")).longValue()).isEqualTo(3316L);
        assertThat(((Number) res.get("fare_vnd")).longValue()).isEqualTo(32000L);

        // processed_commands ghi cùng transaction (response body chỉ mang reservationId/state/version).
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM processed_commands", Integer.class);
        assertThat(total).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void concurrentReservesForOneDriverOnlyOneWins() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);

        UUID resA = UUID.randomUUID();
        UUID tripA = UUID.randomUUID();
        UUID cmdA = UUID.randomUUID();
        UUID resB = UUID.randomUUID();
        UUID tripB = UUID.randomUUID();
        UUID cmdB = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(30);

        CountDownLatch gate = new CountDownLatch(1);
        List<Resp> results = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (UUID[] pair : new UUID[][]{{resA, tripA, cmdA}, {resB, tripB, cmdB}}) {
                pool.submit(() -> {
                    gate.countDown(); // barrier: cả hai start gần như đồng thời
                    try {
                        gate.await();
                        results.add(reserve(pair[0], pair[2], 1, pair[1], driverId, 1, expires, 32000));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long ok = results.stream().filter(r -> r.status() == 201).count();
        long conflict = results.stream().filter(r -> r.status() == 409
                && "DRIVER_NOT_AVAILABLE".equals(r.body().get("code"))).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        // Invariant 1: đúng một reservation hiệu lực cho driver này.
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE driver_id = ? AND state IN ('RESERVED','CONFIRMED')",
                Integer.class, driverId);
        assertThat(active).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void retrySameCommandReplaysWithoutNewRecord() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(30);

        Resp first = reserve(reservationId, commandId, 1, tripId, driverId, 1, expires, 32000);
        assertThat(first.status()).isEqualTo(201);

        // Retry đúng body (cùng commandId + hash) -> replay status/body gốc, không thay đổi state.
        Resp retry = reserve(reservationId, commandId, 1, tripId, driverId, 1, expires, 32000);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().get("reservationId")).isEqualTo(first.body().get("reservationId"));
        assertThat(retry.body().get("state")).isEqualTo("RESERVED");

        assertThat(countReservations()).isEqualTo(1);
        assertThat(countProcessed(commandId)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void sameCommandDifferentPayloadReturns409Conflict() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(30);

        Resp first = reserve(reservationId, commandId, 1, tripId, driverId, 1, expires, 32000);
        assertThat(first.status()).isEqualTo(201);

        // Cùng commandId, payload khác (fareVnd) -> 409 COMMAND_CONFLICT; state không đổi.
        Resp conflict = reserve(reservationId, commandId, 1, tripId, driverId, 1, expires, 99999);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("code")).isEqualTo("COMMAND_CONFLICT");

        assertThat(countReservations()).isEqualTo(1);
        Map<String, Object> driver = jdbc.queryForMap(
                "SELECT status FROM drivers WHERE id = ?", driverId);
        assertThat(driver.get("status")).isEqualTo("RESERVED");
    }

    @org.junit.jupiter.api.Test
    void staleVersionReturns409() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(30);

        Resp first = reserve(reservationId, UUID.randomUUID(), 1, tripId, driverId, 1, expires, 32000);
        assertThat(first.status()).isEqualTo(201);

        // Command mới (commandId khác) nhưng version <= last_command_version đã lưu -> STALE_VERSION.
        Resp stale = reserve(reservationId, UUID.randomUUID(), 1, tripId, driverId, 1, expires, 32000);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("STALE_VERSION");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) stale.body().get("details");
        assertThat(((Number) details.get("storedLastCommandVersion")).intValue()).isEqualTo(1);

        assertThat(countReservations()).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void commandVersionBelowOneReturns400() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        Resp resp = reserve(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(), driverId, 1,
                Instant.now().plusSeconds(30), 32000);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    @org.junit.jupiter.api.Test
    void reserveOfflineDriverReturns409NotAvailable() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "OFFLINE", Instant.now(), 10.7769, 106.7009);
        UUID commandId = UUID.randomUUID();

        Resp resp = reserve(UUID.randomUUID(), commandId, 1, UUID.randomUUID(), driverId, 1,
                Instant.now().plusSeconds(30), 32000);
        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body().get("code")).isEqualTo("DRIVER_NOT_AVAILABLE");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) resp.body().get("details");
        assertThat(details.get("state")).isEqualTo("OFFLINE");

        // Rollback: không có reservation row, không ghi processed_commands, driver giữ state.
        assertThat(countReservations()).isZero();
        assertThat(countProcessed(commandId)).isZero();
        Map<String, Object> driver = jdbc.queryForMap(
                "SELECT status FROM drivers WHERE id = ?", driverId);
        assertThat(driver.get("status")).isEqualTo("OFFLINE");
    }

    @org.junit.jupiter.api.Test
    void reserveStaleHeartbeatReturns409NotAvailable() throws Exception {
        UUID user = UUID.randomUUID();
        // AVAILABLE nhưng heartbeat 60s trước (> HEARTBEAT_STALE_AFTER_MS=15s) -> không chọn được.
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now().minusSeconds(60), 10.7769, 106.7009);

        Resp resp = reserve(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), driverId, 1,
                Instant.now().plusSeconds(30), 32000);
        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body().get("code")).isEqualTo("DRIVER_NOT_AVAILABLE");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) resp.body().get("details");
        assertThat(details.get("state")).isEqualTo("AVAILABLE");

        assertThat(countReservations()).isZero();
    }

    @org.junit.jupiter.api.Test
    void tombstonedReservationBlocksLateReserve() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();

        // Mô phỏng release tới trước reserve (T11 implement endpoint release thật):
        // chèn reservation ở terminal RELEASED cho cùng reservationId.
        jdbc.update(
                "INSERT INTO reservations (id, trip_id, driver_id, attempt, state, terminal_reason,"
                        + " expires_at, last_command_version, pickup_lat, pickup_lng,"
                        + " destination_lat, destination_lng, distance_m, fare_vnd)"
                        + " VALUES (?, ?, ?, 1, 'RELEASED', 'CANCELLED', now() + interval '30 seconds',"
                        + " 2, 10.7769, 106.7009, 10.8000, 10.7200, 3316, 32000)",
                reservationId, UUID.randomUUID(), driverId);

        Resp resp = reserve(reservationId, UUID.randomUUID(), 3, UUID.randomUUID(), driverId, 1,
                Instant.now().plusSeconds(30), 32000);
        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.body().get("code")).isEqualTo("TOMBSTONED_RESERVATION");

        // Invariant 6: không hồi sinh assignment; driver không đổi.
        assertThat(countReservations()).isEqualTo(1);
        Map<String, Object> driver = jdbc.queryForMap(
                "SELECT status FROM drivers WHERE id = ?", driverId);
        assertThat(driver.get("status")).isEqualTo("AVAILABLE");
    }

    @org.junit.jupiter.api.Test
    void getReservationReturns200WithFields() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(30);

        Resp first = reserve(reservationId, UUID.randomUUID(), 1, tripId, driverId, 1, expires, 32000);
        assertThat(first.status()).isEqualTo(201);

        Resp resp = send("GET", "/internal/reservations/" + reservationId, INTERNAL_TOKEN, null);
        assertThat(resp.status()).isEqualTo(200);
        Map<String, Object> body = resp.body();
        assertThat(body.get("reservationId")).isEqualTo(reservationId.toString());
        assertThat(body.get("state")).isEqualTo("RESERVED");
        assertThat(body.get("tripId")).isEqualTo(tripId.toString());
        assertThat(body.get("driverId")).isEqualTo(driverId.toString());
        assertThat(((Number) body.get("attempt")).intValue()).isEqualTo(1);
        assertThat(body.get("expiresAt")).isNotNull();
        assertThat(((Number) body.get("version")).intValue()).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void getUnknownReservationReturns404() throws Exception {
        Resp resp = send("GET", "/internal/reservations/" + UUID.randomUUID(), INTERNAL_TOKEN, null);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.body().get("code")).isEqualTo("NOT_FOUND");
    }

    @org.junit.jupiter.api.Test
    void reserveWithoutInternalTokenReturns401() throws Exception {
        Resp resp = send("PUT", "/internal/reservations/" + UUID.randomUUID(), null,
                reserveJson(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 1,
                        Instant.now().plusSeconds(30), 32000));
        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
    }

    @org.junit.jupiter.api.Test
    void reserveInvalidInputReturns400() throws Exception {
        UUID user = UUID.randomUUID();
        UUID driverId = seedDriver(user, "AVAILABLE", Instant.now(), 10.7769, 106.7009);
        Instant expires = Instant.now().plusSeconds(30);

        // attempt < 1
        Resp r1 = reserve(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), driverId, 0, expires, 32000);
        assertThat(r1.status()).isEqualTo(400);
        assertThat(r1.body().get("code")).isEqualTo("INVALID_INPUT");

        // fareVnd <= 0
        Resp r2 = reserve(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), driverId, 1, expires, -5);
        assertThat(r2.status()).isEqualTo(400);
        assertThat(r2.body().get("code")).isEqualTo("INVALID_INPUT");

        // thiếu commandId
        Resp r3 = send("PUT", "/internal/reservations/" + UUID.randomUUID(), INTERNAL_TOKEN,
                "{\"commandVersion\":1,\"tripId\":\"" + UUID.randomUUID() + "\",\"driverId\":\"" + driverId
                        + "\",\"attempt\":1,\"expiresAt\":\"" + expires.toString()
                        + "\",\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":10.7200},"
                        + "\"distanceM\":3316,\"fareVnd\":32000}");
        assertThat(r3.status()).isEqualTo(400);
        assertThat(r3.body().get("code")).isEqualTo("INVALID_INPUT");

        // tọa độ ngoài khoảng (body thủ công: lat 95)
        Resp r4 = send("PUT", "/internal/reservations/" + UUID.randomUUID(), INTERNAL_TOKEN,
                reserveJson(UUID.randomUUID(), 1, UUID.randomUUID(), driverId, 1, expires, 32000)
                        .replace("\"latitude\":10.7769", "\"latitude\":95"));
        assertThat(r4.status()).isEqualTo(400);
        assertThat(r4.body().get("code")).isEqualTo("INVALID_INPUT");

        assertThat(countReservations()).isZero();
    }
    @org.junit.jupiter.api.Test
    void reviewCommandMustBeBoundToReservationPath() throws Exception {
        UUID driver = seedDriver(UUID.randomUUID(), "AVAILABLE", Instant.now(), 10.7,106.7);
        UUID command=UUID.randomUUID(), trip=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(30);
        assertThat(reserve(a,command,1,trip,driver,1,expires,32000).status()).isEqualTo(201);
        Resp changedPath=reserve(b,command,1,trip,driver,1,expires,32000);
        System.out.println("REVIEW changed reservation path: "+changedPath.status()+" body="+changedPath.body());
        assertThat(changedPath.status()).isEqualTo(409);
        assertThat(changedPath.body().get("code")).isEqualTo("COMMAND_CONFLICT");
    }

    @org.junit.jupiter.api.Test
    void reviewVersionOutsideIntRangeReturns400WithoutAssignment() throws Exception {
        UUID driver = seedDriver(UUID.randomUUID(), "AVAILABLE", Instant.now(),10.7,106.7);
        Resp response=reserve(UUID.randomUUID(),UUID.randomUUID(),2147483648L,UUID.randomUUID(),driver,1,Instant.now().plusSeconds(30),32000);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("code")).isEqualTo("INVALID_INPUT");
        assertThat(countReservations()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM drivers WHERE id=?",String.class,driver)).isEqualTo("AVAILABLE");
    }

    @org.junit.jupiter.api.Test
    void reviewConcurrentSameCommandMustReplay() throws Exception {
        UUID driver=seedDriver(UUID.randomUUID(),"AVAILABLE",Instant.now(),10.7,106.7);
        UUID reservation=UUID.randomUUID(), command=UUID.randomUUID(),trip=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(30);
        var pool=Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Resp>> futures=new java.util.ArrayList<>();
        try (var lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE reservations IN SHARE MODE");
            for(int i=0;i<2;i++) futures.add(pool.submit(()->reserve(reservation,command,1,trip,driver,1,expires,32000)));
            int waiting=0;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline) {
                waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE 'INSERT INTO reservations%' OR query LIKE 'INSERT INTO processed_commands%' OR query LIKE '%pg_advisory_xact_lock%')",Integer.class);
                if(waiting==2) break;
                Thread.sleep(20);
            }
            lock.commit();
            assertThat(waiting).as("both requests passed dedup before insert").isEqualTo(2);
            Resp a=futures.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);
            Resp b=futures.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("REVIEW concurrent same command: "+a.status()+"/"+a.body().get("code")+", "+b.status()+"/"+b.body().get("code"));
            assertThat(List.of(a.status(),b.status())).containsOnly(201);
            assertThat(a.body()).isEqualTo(b.body());
            assertThat(countReservations()).isEqualTo(1);
            assertThat(countProcessed(command)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }


    @org.junit.jupiter.api.Test
    void v3UpgradePreservesSuccessfulReserveReplayAndRealTombstoneConstraints() throws Exception {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA "+schema);
        String url=POSTGRES.getJdbcUrl()+(POSTGRES.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema;
        var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword());
        var legacy=new JdbcTemplate(dataSource);
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=UUID.randomUUID(),cmd=UUID.randomUUID();
            Instant expires=Instant.parse("2026-10-20T12:00:00Z");
            String canonical="attempt=1|distanceM=3316|driverId="+driver+"|expiresAt="+expires+"|fareVnd=32000|pickupLat=10.7769|pickupLng=106.7009|destLat=10.8|destLng=10.72|tripId="+trip;
            String oldHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            String stored="{\"status\":201,\"body\":{\"reservationId\":\""+r+"\",\"state\":\"RESERVED\",\"version\":1}}";
            legacy.update("INSERT INTO reservations(id,trip_id,driver_id,attempt,state,expires_at,last_command_version,pickup_lat,pickup_lng,destination_lat,destination_lng,distance_m,fare_vnd) VALUES (?,?,?,1,'RESERVED',?,1,10.7769,106.7009,10.8,10.72,3316,32000)",r,trip,driver,Timestamp.from(expires));
            legacy.update("INSERT INTO processed_commands(command_id,request_hash,stored_response) VALUES (?,?,?::jsonb)",cmd,oldHash,stored);
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            flyway.migrate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
            var repo=new com.ridehailing.driver.repo.ReservationRepository(legacy);
            var service=new com.ridehailing.driver.service.ReservationService(new com.ridehailing.driver.repo.DriverRepository(legacy),repo,JSON,15000);
            var request=new com.ridehailing.driver.web.dto.ReserveRequest(cmd,1,trip,driver,1,expires,
                    new com.ridehailing.driver.web.dto.ReserveRequest.GeoPoint(10.7769,106.7009),new com.ridehailing.driver.web.dto.ReserveRequest.GeoPoint(10.8,10.72),3316,32000);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            var replay=tx.execute(status->service.reserve(r,request));
            assertThat(replay.httpStatus()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(parse(stored).get("body"));
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM reservations",Integer.class)).isEqualTo(1);
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM processed_commands",Integer.class)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->tx.execute(status->service.reserve(UUID.randomUUID(),request)))
                    .isInstanceOf(com.ridehailing.driver.exception.CommandConflictException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->legacy.update("INSERT INTO reservations(id,state,last_command_version) VALUES (?,'RELEASED',2)",UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->legacy.update("INSERT INTO reservations(id,state,last_command_version) VALUES (?,'RESERVED',1)",UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            repo.insertTombstone(UUID.randomUUID(),2,"CANCELLED");
        } finally {
            jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }

    @org.junit.jupiter.api.Test
    void concurrentCommandIdWithDifferentPayloadReturnsConflictAndRollsBackLoser() throws Exception {
        UUID driverA=seedDriver(UUID.randomUUID(),"AVAILABLE",Instant.now(),10.7,106.7);
        UUID driverB=seedDriver(UUID.randomUUID(),"AVAILABLE",Instant.now(),10.7,106.7);
        UUID cmd=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(30);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{barrier.await();return reserve(UUID.randomUUID(),cmd,1,UUID.randomUUID(),driverA,1,expires,32000);});
            var second=pool.submit(()->{barrier.await();return reserve(UUID.randomUUID(),cmd,1,UUID.randomUUID(),driverB,1,expires,33000);});
            var responses=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses.stream().map(Resp::status).toList()).containsExactlyInAnyOrder(201,409);
            assertThat(responses.stream().filter(r->r.status()==409).findFirst().orElseThrow().body().get("code")).isEqualTo("COMMAND_CONFLICT");
        }
        assertThat(countReservations()).isEqualTo(1);
        assertThat(countProcessed(cmd)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drivers WHERE status='RESERVED'",Integer.class)).isEqualTo(1);
    }


    @org.junit.jupiter.api.Test
    void maxIntCommandVersionIsStoredAndReadWithoutTruncation() throws Exception {
        UUID driver=seedDriver(UUID.randomUUID(),"AVAILABLE",Instant.now(),10.7,106.7),r=UUID.randomUUID();
        Resp created=reserve(r,UUID.randomUUID(),Integer.MAX_VALUE,UUID.randomUUID(),driver,1,Instant.now().plusSeconds(30),32000);
        assertThat(created.status()).isEqualTo(201);
        assertThat(((Number)created.body().get("version")).longValue()).isEqualTo(2147483647L);
        Resp read=send("GET","/internal/reservations/"+r,INTERNAL_TOKEN,null);
        assertThat(((Number)read.body().get("version")).longValue()).isEqualTo(2147483647L);
        assertThat(jdbc.queryForObject("SELECT last_command_version FROM reservations WHERE id=?",Long.class,r)).isEqualTo(2147483647L);
    }

}
