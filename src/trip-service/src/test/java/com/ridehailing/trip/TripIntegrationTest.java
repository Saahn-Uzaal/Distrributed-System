package com.ridehailing.trip;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ridehailing.trip.repo.IdempotencyKeyRepository;
import com.ridehailing.trip.repo.OutboxRepository;
import com.ridehailing.trip.repo.TripEventRepository;
import com.ridehailing.trip.repo.TripRepository;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.Role;
import com.ridehailing.trip.service.TripService;
import com.ridehailing.trip.web.dto.QuoteRequest;
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12 — integration test quote/create/list/get trên HTTP thật + PostgreSQL thật (Testcontainers, cần Docker).
 * Flyway chạy V1+V2+V3 lên DB container. Acceptance T12:
 *  - cùng key/payload trả cùng trip (replay response gốc); khác payload -> 409 IDEMPOTENCY_MISMATCH;
 *  - giá tính phía server (haversine + công thức fare env); chỉ owner xem được trip; state SEARCHING;
 *  - ý định matching ghi trong DB: outbox PENDING TripRequested attempt 1 + trip_event TRIP_CREATED.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TripIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("trip_test")
            .withPassword("trip_test_pw");

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-trip-t12");
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
        // Bean JwtVerifier vẫn khởi tạo dù test không verify token thật — dùng public key test.
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    record Resp(int status, Map<String, Object> body, Map<String, List<String>> headers) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM trip_events");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM trips");
    }

    // --- helpers HTTP/JWT/DB ---

    /** JWT RS256 test: claims đúng contract §2 (iss, aud chứa trip-service, sub UUID, role).
     * Pattern ký khớp JwtService identity-service (T06). */
    private static String token(UUID userId, String role) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("email", role.toLowerCase() + "-t12@example.com");
        claims.put("role", role);
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of("identity-service", "driver-service", "trip-service",
                "matching-service", "payment-service"));
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(
                new com.nimbusds.jose.JWSHeader(JWSAlgorithm.RS256), new com.nimbusds.jose.Payload(claims));
        try {
            jws.sign(SIGNER);
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Cannot sign test JWT.", e);
        }
        return jws.serialize();
    }

    private Resp send(String method, String path, String bearerToken, Map<String, String> extraHeaders, String jsonBody) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path));
        switch (method) {
            case "GET" -> req.GET();
            case "POST" -> req.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            default -> throw new IllegalArgumentException(method);
        }
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        extraHeaders.forEach(req::header);
        req.header("Content-Type", "application/json");
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()), resp.headers().map());
    }

    private static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
        return m;
    }

    private static final String QUOTE_BODY = "{\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
            + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}";
    private static final String OTHER_DEST_BODY = "{\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
            + "\"destination\":{\"latitude\":10.8500,\"longitude\":106.7500}}";

    // --- tests ---

    @org.junit.jupiter.api.Test
    void quoteReturnsServerComputedFare() throws Exception {
        Resp resp = send("POST", "/api/trips/quote", token(UUID.randomUUID(), "PASSENGER"), Map.of(), QUOTE_BODY);
        assertThat(resp.status()).isEqualTo(200);
        // Haversine (R=6371008.8) cho cặp tọa độ này = 3309 m; fare = 12000 + ceil(3309/1000)*5000 = 32000.
        assertThat(((Number) resp.body().get("distanceM")).longValue()).isEqualTo(3309);
        assertThat(((Number) resp.body().get("fareVnd")).longValue()).isEqualTo(32000);

        // Quote không tạo trip, không side effect.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    }

    @org.junit.jupiter.api.Test
    void quoteRejectsInvalidCoordinates() throws Exception {
        String token = token(UUID.randomUUID(), "PASSENGER");
        Resp badLat = send("POST", "/api/trips/quote", token, Map.of(),
                "{\"pickup\":{\"latitude\":95,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}");
        assertThat(badLat.status()).isEqualTo(400);
        assertThat(badLat.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp missing = send("POST", "/api/trips/quote", token, Map.of(), "{\"pickup\":null,\"destination\":null}");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    @org.junit.jupiter.api.Test
    void createReturns202LocationAndSearchStateWithMatchingIntent() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();

        Resp resp = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(resp.status()).isEqualTo(202);
        String tripId = (String) resp.body().get("tripId");
        assertThat(tripId).isNotNull();
        assertThat(resp.headers().get("Location")).contains("/api/trips/" + tripId);
        assertThat(resp.body().get("state")).isEqualTo("SEARCHING");
        assertThat(((Number) resp.body().get("matchingAttempt")).intValue()).isEqualTo(1);
        assertThat(resp.body().get("createdAt")).isNotNull();

        // DB: trip SEARCHING version 1, fare server-side.
        UUID dbTripId = UUID.fromString(tripId);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, matching_attempt, version, distance_m, fare_vnd, passenger_id FROM trips WHERE id = ?",
                dbTripId);
        assertThat(row.get("state")).isEqualTo("SEARCHING");
        assertThat(((Number) row.get("matching_attempt")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("distance_m")).longValue()).isEqualTo(3309);
        assertThat(((Number) row.get("fare_vnd")).longValue()).isEqualTo(32000);

        // Ý định matching: outbox PENDING TripRequested attempt 1 + trip_event TRIP_CREATED v1.
        // payload::text — pgjdbc trả JSONB qua getObject() là PGobject, không phải String.
        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT event_type, aggregate_id, payload::text AS payload, status FROM outbox WHERE aggregate_id = ?", dbTripId);
        assertThat(outbox.get("event_type")).isEqualTo("TripRequested");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) JSON.convertValue(
                JSON.readTree((String) outbox.get("payload")), LinkedHashMap.class);
        assertThat(payload.get("tripId")).isEqualTo(tripId);
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT type, version, actor_id FROM trip_events WHERE trip_id = ?", dbTripId);
        assertThat(event.get("type")).isEqualTo("TRIP_CREATED");
        assertThat(((Number) event.get("version")).intValue()).isEqualTo(1);
        assertThat(event.get("actor_id").toString()).isEqualTo(passenger.toString());
    }

    @org.junit.jupiter.api.Test
    void replaySameKeySamePayloadReturnsOriginalResponse() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();

        Resp first = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(first.status()).isEqualTo(202);
        String tripId = (String) first.body().get("tripId");

        // Retry client: cùng key + cùng payload -> response gốc đã lưu, không tạo trip mới.
        Resp replay = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.body().get("tripId")).isEqualTo(tripId);
        assertThat(replay.headers().get("Location")).contains("/api/trips/" + tripId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Integer.class))
                .isEqualTo(1);
    }

    /** T12-1 (R03): createdAt sinh một lần phía server — GET, DB và replay trả cùng giá trị. */
    @org.junit.jupiter.api.Test
    void createdAtIsStableAcrossGetAndReplay() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();

        Resp first = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(first.status()).isEqualTo(202);
        String tripId = (String) first.body().get("tripId");
        String createdAt = (String) first.body().get("createdAt");

        Resp detail = send("GET", "/api/trips/" + tripId, token, Map.of(), null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("createdAt")).isEqualTo(createdAt);

        String dbCreatedAt = jdbc.queryForObject(
                "SELECT created_at FROM trips WHERE id=?", Instant.class, UUID.fromString(tripId)).toString();
        assertThat(dbCreatedAt).isEqualTo(createdAt);

        Resp replay = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.body().get("tripId")).isEqualTo(tripId);
        assertThat(replay.body().get("createdAt")).isEqualTo(createdAt);
    }

    /** T12-2 (N31): outbox envelope — aggregate_version=1, routing_key='trip.requested' sau create. */
    @org.junit.jupiter.api.Test
    void outboxRowCarriesAggregateVersionAndRoutingKey() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        Resp resp = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        assertThat(resp.status()).isEqualTo(202);
        UUID tripId = UUID.fromString((String) resp.body().get("tripId"));

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT aggregate_version, schema_version, producer, routing_key, attempts FROM outbox WHERE aggregate_id = ?", tripId);
        assertThat(((Number) outbox.get("aggregate_version")).intValue()).isEqualTo(1);
        assertThat(outbox.get("routing_key")).isEqualTo("trip.requested");
    }

    /** T12-4 (R01): canonical double — latitude 0.0 và -0.0 cùng hash -> replay 202, không 409 MISMATCH. */
    @org.junit.jupiter.api.Test
    void negativeZeroLatitudeReplaysInsteadOfMismatch() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();

        Resp first = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()),
                "{\"pickup\":{\"latitude\":0.0,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}");
        assertThat(first.status()).isEqualTo(202);

        Resp replay = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()),
                "{\"pickup\":{\"latitude\":-0.0,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}");
        assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.body().get("tripId")).isEqualTo(first.body().get("tripId"));
    }

    /** T12-5 (N32): list chạy REPEATABLE READ — totalElements luôn khớp content dù có create đồng thời. */
    @org.junit.jupiter.api.Test
    void listUnderRepeatableReadStaysConsistentWithConcurrentCreates() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");

        for (int i = 0; i < 2; i++) {
            Resp c = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
            assertThat(c.status()).isEqualTo(202);
        }

        var executor = Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<Resp>> creates = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                creates.add(executor.submit(() -> send("POST", "/api/trips", token,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY)));
            }
            java.util.concurrent.Future<Resp> listFuture = executor.submit(
                    () -> send("GET", "/api/trips?page=0&size=20", token, Map.of(), null));
            for (var f : creates) {
                Resp r = f.get(15, TimeUnit.SECONDS);
                assertThat(r.status()).isEqualTo(202);
            }
            Resp list = listFuture.get(15, TimeUnit.SECONDS);
            assertThat(list.status()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) list.body().get("content");
            long totalElements = ((Number) list.body().get("totalElements")).longValue();
            // Snapshot REPEATABLE READ: size=20 bao phủ mọi row -> totalElements == |content|.
            assertThat(totalElements).isEqualTo(content.size());
            assertThat(content.size()).isBetween(2, 6);

            long finalCount = jdbc.queryForObject("SELECT COUNT(*) FROM trips WHERE passenger_id=?", Long.class, passenger);
            assertThat(finalCount).isEqualTo(6);
        } finally {
            executor.shutdownNow();
        }
    }

    @org.junit.jupiter.api.Test
    void sameKeyDifferentPayloadReturns409Mismatch() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();

        Resp first = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(first.status()).isEqualTo(202);

        Resp mismatch = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), OTHER_DEST_BODY);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.body().get("code")).isEqualTo("IDEMPOTENCY_MISMATCH");

        // Payload khác không tạo trip mới.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void sameKeyAndPayloadFromDifferentPassengersCreatesIndependentTrips() throws Exception {
        assertIndependentPassengerCreates(QUOTE_BODY);
    }

    @org.junit.jupiter.api.Test
    void sameKeyWithDifferentPayloadFromDifferentPassengersCreatesIndependentTrips() throws Exception {
        assertIndependentPassengerCreates(OTHER_DEST_BODY);
    }

    private void assertIndependentPassengerCreates(String secondBody) throws Exception {
        UUID firstPassenger = UUID.randomUUID();
        UUID secondPassenger = UUID.randomUUID();
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        Resp first = send("POST", "/api/trips", token(firstPassenger, "PASSENGER"), headers, QUOTE_BODY);
        Resp second = send("POST", "/api/trips", token(secondPassenger, "PASSENGER"), headers, secondBody);
        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(202);
        assertThat(second.body().get("tripId")).isNotEqualTo(first.body().get("tripId"));
        assertThat(jdbc.queryForObject("SELECT passenger_id FROM trips WHERE id = ?", UUID.class,
                UUID.fromString((String) second.body().get("tripId")))).isEqualTo(secondPassenger);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(2);
        assertThat(send("GET", "/api/trips/" + first.body().get("tripId"),
                token(secondPassenger, "PASSENGER"), Map.of(), null).status()).isEqualTo(404);
    }

    @org.junit.jupiter.api.Test
    void simultaneousSameOwnerSameKeyReplaysOneTripAndOutbox() throws Exception {
        String bearer = token(UUID.randomUUID(), "PASSENGER");
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        try (var lock = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("LOCK TABLE idempotency_keys IN SHARE MODE");
            var first = executor.submit(() -> send("POST", "/api/trips", bearer, headers, QUOTE_BODY));
            var second = executor.submit(() -> send("POST", "/api/trips", bearer, headers, QUOTE_BODY));
            try {
                Instant deadline = Instant.now().plusSeconds(10);
                int blocked;
                do {
                    blocked = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                            + "AND lower(query) LIKE 'insert into idempotency_keys%'", Integer.class);
                    if (blocked < 2) Thread.sleep(20);
                } while (blocked < 2 && Instant.now().isBefore(deadline));
                assertThat(blocked).as("both HTTP requests overlap at idempotency claim").isEqualTo(2);
            } finally {
                lock.commit();
            }
            Resp firstResponse = first.get(10, TimeUnit.SECONDS);
            Resp secondResponse = second.get(10, TimeUnit.SECONDS);
            assertThat(firstResponse.status()).isEqualTo(202);
            assertThat(secondResponse.status()).isEqualTo(202);
            assertThat(secondResponse.body()).isEqualTo(firstResponse.body());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void missingOrInvalidIdempotencyKeyReturns400() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");

        Resp missing = send("POST", "/api/trips", token, Map.of(), QUOTE_BODY);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp badKey = send("POST", "/api/trips", token, Map.of("Idempotency-Key", "not-a-uuid"), QUOTE_BODY);
        assertThat(badKey.status()).isEqualTo(400);
        assertThat(badKey.body().get("code")).isEqualTo("INVALID_INPUT");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isZero();
    }

    @org.junit.jupiter.api.Test
    void createRejectsInvalidCoordinates() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        Resp resp = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"pickup\":{\"latitude\":-91,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}");
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isZero();
    }

    @org.junit.jupiter.api.Test
    void listShowsOwnTripsWithPaginationEnvelope() throws Exception {
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();
        Resp created = send("POST", "/api/trips", token, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(created.status()).isEqualTo(202);

        Resp list = send("GET", "/api/trips?page=0&size=20", token, Map.of(), null);
        assertThat(list.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) list.body().get("content");
        assertThat(content).hasSize(1);
        assertThat(((Number) list.body().get("page")).intValue()).isZero();
        assertThat(((Number) list.body().get("size")).intValue()).isEqualTo(20);
        assertThat(((Number) list.body().get("totalElements")).longValue()).isEqualTo(1);
        assertThat(((Number) list.body().get("totalPages")).intValue()).isEqualTo(1);

        Map<String, Object> item = content.get(0);
        assertThat(item.get("tripId")).isEqualTo(created.body().get("tripId"));
        assertThat(item.get("state")).isEqualTo("SEARCHING");
        assertThat(((Number) item.get("distanceM")).longValue()).isEqualTo(3309);
        assertThat(((Number) item.get("fareVnd")).longValue()).isEqualTo(32000);
        assertThat(item.get("paymentStatus")).isEqualTo("NOT_REQUESTED");
        assertThat(item.get("pickup")).isNotNull();
        assertThat(item.get("destination")).isNotNull();

        // Clamp size > 100 về 100 (§1.3).
        Resp clamped = send("GET", "/api/trips?page=0&size=999", token, Map.of(), null);
        assertThat(clamped.status()).isEqualTo(200);
        assertThat(((Number) clamped.body().get("size")).intValue()).isEqualTo(100);

        // Page ngoài range -> content rỗng nhưng envelope hợp lệ.
        Resp emptyPage = send("GET", "/api/trips?page=5&size=20", token, Map.of(), null);
        assertThat(emptyPage.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> emptyContent = (List<Map<String, Object>>) emptyPage.body().get("content");
        assertThat(emptyContent).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void getDetailOwnerSeesTripOtherPassengerGets404() throws Exception {
        UUID owner = UUID.randomUUID();
        String ownerToken = token(owner, "PASSENGER");
        UUID key = UUID.randomUUID();
        Resp created = send("POST", "/api/trips", ownerToken, Map.of("Idempotency-Key", key.toString()), QUOTE_BODY);
        assertThat(created.status()).isEqualTo(202);
        String tripId = (String) created.body().get("tripId");

        // Owner: 200 + đầy đủ field detail.
        Resp own = send("GET", "/api/trips/" + tripId, ownerToken, Map.of(), null);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body().get("tripId")).isEqualTo(tripId);
        assertThat(own.body().get("state")).isEqualTo("SEARCHING");
        assertThat(own.body().get("driverId")).isNull();
        assertThat(((Number) own.body().get("matchingAttempt")).intValue()).isEqualTo(1);
        assertThat(own.body().get("paymentStatus")).isEqualTo("NOT_REQUESTED");
        assertThat(own.body().get("createdAt")).isNotNull();
        assertThat(own.body().get("updatedAt")).isNotNull();

        // Passenger khác: 404 (không lộ sự tồn tại trip).
        String otherToken = token(UUID.randomUUID(), "PASSENGER");
        Resp other = send("GET", "/api/trips/" + tripId, otherToken, Map.of(), null);
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.body().get("code")).isEqualTo("NOT_FOUND");

        // Không token: 401 (filter T07).
        Resp anon = send("GET", "/api/trips/" + tripId, null, Map.of(), null);
        assertThat(anon.status()).isEqualTo(401);
        assertThat(anon.body().get("code")).isEqualTo("UNAUTHORIZED");

        // Trip không tồn tại: 404.
        Resp missing = send("GET", "/api/trips/" + UUID.randomUUID(), ownerToken, Map.of(), null);
        assertThat(missing.status()).isEqualTo(404);
    }

    @org.junit.jupiter.api.Test
    void driverSeesOnlyAssignedTrips() throws Exception {
        // T12 chưa có assignment (driver_id luôn NULL) -> list của DRIVER rỗng.
        UUID passenger = UUID.randomUUID();
        send("POST", "/api/trips", token(passenger, "PASSENGER"), Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);

        Resp driverList = send("GET", "/api/trips?page=0&size=20", token(UUID.randomUUID(), "DRIVER"), Map.of(), null);
        assertThat(driverList.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) driverList.body().get("content");
        assertThat(content).isEmpty();
        assertThat(((Number) driverList.body().get("totalElements")).longValue()).isZero();
    }

    @org.junit.jupiter.api.Test
    void assignedDriverUsesIdentityUserIdForListCountAndDetail() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'trips' AND column_name = 'driver_user_id'", Integer.class))
                .as("Trip must persist the assigned driver's identity independently of profile ID").isEqualTo(1);
        UUID passenger = UUID.randomUUID();
        UUID driverProfile = UUID.randomUUID();
        UUID driverUser = UUID.randomUUID();
        Resp created = send("POST", "/api/trips", token(passenger, "PASSENGER"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        UUID tripId = UUID.fromString((String) created.body().get("tripId"));
        jdbc.update("UPDATE trips SET driver_id = ?, driver_user_id = ? WHERE id = ?",
                driverProfile, driverUser, tripId);

        Resp list = send("GET", "/api/trips", token(driverUser, "DRIVER"), Map.of(), null);
        assertThat(list.status()).isEqualTo(200);
        assertThat(((Number) list.body().get("totalElements")).longValue()).isEqualTo(1);
        assertThat((List<?>) list.body().get("content")).hasSize(1);
        Resp detail = send("GET", "/api/trips/" + tripId, token(driverUser, "DRIVER"), Map.of(), null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("driverId")).isEqualTo(driverProfile.toString());

        // Even a JWT whose subject equals the profile UUID does not represent its assigned user.
        for (UUID stranger : List.of(UUID.randomUUID(), driverProfile)) {
            Resp deniedList = send("GET", "/api/trips", token(stranger, "DRIVER"), Map.of(), null);
            assertThat(deniedList.status()).isEqualTo(200);
            assertThat(((Number) deniedList.body().get("totalElements")).longValue()).isZero();
            assertThat((List<?>) deniedList.body().get("content")).isEmpty();
            assertThat(send("GET", "/api/trips/" + tripId, token(stranger, "DRIVER"), Map.of(), null).status())
                    .isEqualTo(404);
        }
        assertThat(send("GET", "/api/trips/" + tripId, token(passenger, "PASSENGER"), Map.of(), null).status())
                .isEqualTo(200);
    }

    @org.junit.jupiter.api.Test
    void tripReadAuthorizationRequiresTheMatchingPassengerOrDriverRole() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID driverUser = UUID.randomUUID();
        Resp created = send("POST", "/api/trips", token(passenger, "PASSENGER"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        UUID tripId = UUID.fromString((String) created.body().get("tripId"));
        jdbc.update("UPDATE trips SET driver_id = ?, driver_user_id = ? WHERE id = ?",
                UUID.randomUUID(), driverUser, tripId);
        for (String bearer : List.of(token(passenger, "DRIVER"), token(driverUser, "PASSENGER"))) {
            Resp list = send("GET", "/api/trips", bearer, Map.of(), null);
            assertThat(list.status()).isEqualTo(200);
            assertThat((List<?>) list.body().get("content")).isEmpty();
            assertThat(((Number) list.body().get("totalElements")).longValue()).isZero();
            assertThat(send("GET", "/api/trips/" + tripId, bearer, Map.of(), null).status()).isEqualTo(404);
        }
    }

    @org.junit.jupiter.api.Test
    void veryLargePagesDoNotOverflowToNegativeOrEarlierOffsets() throws Exception {
        String bearer = token(UUID.randomUUID(), "PASSENGER");
        send("POST", "/api/trips", bearer, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        for (String query : List.of("page=2147483647&size=100", "page=1073741824&size=4")) {
            Resp page = send("GET", "/api/trips?" + query, bearer, Map.of(), null);
            assertThat(page.status()).isEqualTo(200);
            assertThat((List<?>) page.body().get("content")).isEmpty();
            assertThat(((Number) page.body().get("totalElements")).longValue()).isEqualTo(1);
        }
    }

    @org.junit.jupiter.api.Test
    void v2UpgradePreservesOriginalOwnerReplayAndEnforcesCompleteAssignmentIdentity() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        var dataSource = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var legacy = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            UUID passenger = UUID.randomUUID();
            UUID key = UUID.randomUUID();
            UUID tripId = UUID.randomUUID();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    "destLat=10.8|destLng=106.72|pickupLat=10.7769|pickupLng=106.7009"
                            .getBytes(StandardCharsets.UTF_8)));
            String originalResponse = "{\"status\":202,\"body\":{\"tripId\":\"" + tripId
                    + "\",\"state\":\"SEARCHING\",\"matchingAttempt\":1,\"createdAt\":\"2026-10-19T00:00:00Z\"}}";
            legacy.update("INSERT INTO trips(id, passenger_id, pickup_lat, pickup_lng, destination_lat, "
                    + "destination_lng, distance_m, fare_vnd) VALUES (?, ?, 10.7769, 106.7009, 10.8, 106.72, 3309, 32000)",
                    tripId, passenger);
            legacy.update("INSERT INTO idempotency_keys(key, request_hash, stored_response) VALUES (?, ?, ?::jsonb)",
                    key, hash, originalResponse);
            var upgraded = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
            upgraded.migrate();
            assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("4");
            assertThat(legacy.queryForObject("SELECT principal_id FROM idempotency_keys WHERE key = ?", UUID.class, key))
                    .isEqualTo(passenger);
            assertThat(legacy.queryForObject("SELECT operation FROM idempotency_keys WHERE key = ?", String.class, key))
                    .isEqualTo("CREATE_TRIP");
            var service = new TripService(new TripRepository(legacy), new OutboxRepository(legacy),
                    new TripEventRepository(legacy), new IdempotencyKeyRepository(legacy, JSON), JSON, 12000, 5000);
            var replay = service.create(new AuthenticatedUser(passenger, "legacy@example.com", Role.PASSENGER),
                    key.toString(), new QuoteRequest(new QuoteRequest.Point(10.7769, 106.7009),
                            new QuoteRequest.Point(10.8, 106.72)), UUID.randomUUID());
            assertThat(replay.httpStatus()).isEqualTo(202);
            assertThat(replay.body()).isEqualTo(parse(originalResponse).get("body"));
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM trips", Integer.class)).isEqualTo(1);
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();

            assertThatThrownBy(() -> legacy.update("UPDATE trips SET driver_id = ? WHERE id = ?",
                    UUID.randomUUID(), tripId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update("UPDATE trips SET driver_user_id = ? WHERE id = ?",
                    UUID.randomUUID(), tripId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(legacy.update("UPDATE trips SET driver_id = ?, driver_user_id = ? WHERE id = ?",
                    UUID.randomUUID(), UUID.randomUUID(), tripId)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @org.junit.jupiter.api.Test
    void passengerCannotCallDriverEndpointsAndViceVersa() throws Exception {
        // POST /api/trips với role DRIVER -> 403 FORBIDDEN (filter T07).
        Resp driverCreate = send("POST", "/api/trips", token(UUID.randomUUID(), "DRIVER"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        assertThat(driverCreate.status()).isEqualTo(403);
        assertThat(driverCreate.body().get("code")).isEqualTo("FORBIDDEN");

        // POST /api/trips/quote với role DRIVER -> 403.
        Resp driverQuote = send("POST", "/api/trips/quote", token(UUID.randomUUID(), "DRIVER"), Map.of(), QUOTE_BODY);
        assertThat(driverQuote.status()).isEqualTo(403);
    }

    /** N12/N13 — X-Correlation-ID: UUID không canonical được chuẩn hóa; header trả về trong mọi response;
     *  outbox dùng đúng id canonical. */
    @org.junit.jupiter.api.Test
    void correlationIdIsCanonicalizedAndReturnedInEveryResponse() throws Exception {
        String nonCanonical = "1-2-3-4-5";
        // UUID.fromString("1-2-3-4-5").toString() — canonical 36-char lowercase.
        String expected = "00000001-0002-0003-0004-000000000005";

        // 400 error: body.correlationId + response header đều canonical.
        Resp badQuote = send("POST", "/api/trips/quote", token(UUID.randomUUID(), "PASSENGER"),
                Map.of("X-Correlation-ID", nonCanonical),
                "{\"pickup\":{\"latitude\":95,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}");
        assertThat(badQuote.status()).isEqualTo(400);
        assertThat(badQuote.body().get("correlationId")).isEqualTo(expected);
        assertThat(badQuote.headers().get("X-Correlation-ID")).containsExactly(expected);

        // 202 success: header canonical; outbox.correlation_id = canonical.
        UUID passenger = UUID.randomUUID();
        Resp created = send("POST", "/api/trips", token(passenger, "PASSENGER"),
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "X-Correlation-ID", nonCanonical), QUOTE_BODY);
        assertThat(created.status()).isEqualTo(202);
        assertThat(created.headers().get("X-Correlation-ID")).containsExactly(expected);
        UUID tripId = UUID.fromString((String) created.body().get("tripId"));
        String stored = jdbc.queryForObject("SELECT correlation_id FROM outbox WHERE aggregate_id = ?", String.class, tripId);
        assertThat(stored).isEqualTo(expected);

        // Không có header: server tự sinh UUID hợp lệ.
        Resp list = send("GET", "/api/trips?page=0&size=20", token(passenger, "PASSENGER"), Map.of(), null);
        List<String> generated = list.headers().get("X-Correlation-ID");
        assertThat(generated).hasSize(1);
        UUID.fromString(generated.get(0)); // parse được -> UUID hợp lệ
    }

    /** N11 — audit log trip_created: structured, MDC tripId, không ghi secret. */
    @org.junit.jupiter.api.Test
    void tripCreatedAuditLogIsStructuredAndSecretFree() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.ridehailing.trip.service.TripService");
        com.ridehailing.trip.logging.JsonLogLayout layout = new com.ridehailing.trip.logging.JsonLogLayout();
        layout.setService("trip-service");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.setName("AUDIT_PROBE");
        appender.setOutputStream(out);
        appender.setLayout(layout);
        appender.start();

        UUID passenger = UUID.randomUUID();
        logger.addAppender(appender);
        try {
            Resp created = send("POST", "/api/trips", token(passenger, "PASSENGER"),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
            assertThat(created.status()).isEqualTo(202);
            String tripId = (String) created.body().get("tripId");

            String text = out.toString(StandardCharsets.UTF_8);
            Map<String, Object> audit = null;
            for (String line : Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.readValue(line.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
                if ("trip_created".equals(m.get("eventType"))) {
                    audit = m;
                    break;
                }
            }
            assertThat(audit).isNotNull();
            assertThat(audit.get("result")).isEqualTo("SUCCESS");
            assertThat(audit.get("tripId")).isEqualTo(tripId);
            // Không secret: không Bearer token, không email.
            assertThat(text).doesNotContain("Bearer ").doesNotContain("@example.com");
        } finally {
            logger.detachAndStopAllAppenders();
        }
    }
}
