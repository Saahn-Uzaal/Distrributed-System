package com.ridehailing.trip;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.ridehailing.trip.event.EventEnvelope;
import com.ridehailing.trip.service.TripInboxHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.ServerSocket;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nền chung cho test T18–T20: HTTP thật (RANDOM_PORT) + PostgreSQL thật + RabbitMQ thật (listener/outbox
 * của T13/T16 chạy bình thường) + {@link StatefulDriverStub} thay driver-service.
 * Chuyến được đưa vào DRIVER_OFFERED bằng chính {@link TripInboxHandler} của T16 (DriverProposed).
 */
abstract class TripCommandTestSupport {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withUsername("trip_cmd_test")
            .withPassword("trip_cmd_test_pw");

    static final int AMQP_HOST_PORT = freeLocalPort();

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:3.13.7-management-alpine"))
            .withAdminUser("ridehailing_dev")
            .withAdminPassword("rabbit_dev_2026")
            .withExposedPorts(5672)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", AMQP_HOST_PORT), new ExposedPort(5672))));

    static final StatefulDriverStub DRIVER = new StatefulDriverStub();

    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;

    static {
        try {
            Path dir = Files.createTempDirectory("jwt-test-keys-trip-cmd");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = dir.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
            SIGNER = new RSASSASigner(pair.getPrivate());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static int freeLocalPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(AMQP_HOST_PORT));
        registry.add("spring.rabbitmq.username", () -> "ridehailing_dev");
        registry.add("spring.rabbitmq.password", () -> "rabbit_dev_2026");
        registry.add("app.trip.driver-service-url", () -> "http://localhost:" + DRIVER.port());
        registry.add("app.trip.internal-api-token", () -> "test_internal_token_2026");
        // Read timeout 800 ms; stub trễ 1500 ms để mô phỏng timeout thật của RestClient.
        registry.add("app.trip.http-timeout-ms", () -> "800");
        registry.add("app.outbox.publish-interval-ms", () -> "200");
        // T21 — tắt worker schedule trong test fault-injection (deterministic): tick 1 s của
        // SagaRecoveryWorker có thể claim intent treo giữa commit intent và HTTP call, tiêu thụ
        // fault flag trước request. Hành vi worker được test tường minh qua processDueSagas()
        // ở TripSagaRecoveryIntegrationTest / TripSagaRestartIntegrationTest (cùng code path với tick).
        registry.add("app.saga.worker-interval-ms", () -> "3600000");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TripInboxHandler inboxHandler;
    @Autowired
    PlatformTransactionManager txManager;

    @LocalServerPort
    int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    record Resp(int status, Map<String, Object> body) {
    }

    /** Một chuyến đang DRIVER_OFFERED: passenger, driver được offer và reservation trên stub. */
    record Offered(UUID tripId, UUID passengerUserId, UUID driverId, UUID driverUserId, UUID reservationId, int attempt) {
    }

    @BeforeEach
    void resetStub() {
        DRIVER.reset();
    }

    @AfterEach
    void cleanTables() {
        // T25: notifications có FK vào trips — xóa trước trips.
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM sagas");
        jdbc.update("DELETE FROM trip_events");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM trips");
    }

    // ---------- JWT + HTTP ----------

    static String token(UUID userId, String role) throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("email", role.toLowerCase() + "-cmd@example.com");
        claims.put("role", role);
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of("trip-service"));
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jws.sign(SIGNER);
        return jws.serialize();
    }

    Resp post(String path, String bearer, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(req::header);
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    Resp post(String path, String bearer, String jsonBody) throws Exception {
        return post(path, bearer, jsonBody, Map.of());
    }

    static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
        return m;
    }

    Resp accept(Offered o, UUID callerUserId, int attempt) throws Exception {
        return post("/api/trips/" + o.tripId() + "/accept", token(callerUserId, "DRIVER"), "{\"attempt\":" + attempt + "}");
    }

    // ---------- dựng chuyến ----------

    UUID createTrip(UUID passengerUserId) throws Exception {
        Resp r = post("/api/trips", token(passengerUserId, "PASSENGER"),
                "{\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                        + "\"destination\":{\"latitude\":10.8,\"longitude\":106.72}}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.status()).isEqualTo(202);
        return UUID.fromString((String) r.body().get("tripId"));
    }

    /** Gửi DriverProposed (1 candidate) cho attempt hiện tại qua handler T16 → DRIVER_OFFERED. */
    Offered offer(UUID tripId, UUID passengerUserId) {
        int attempt = tripInt(tripId, "matching_attempt");
        UUID driverId = UUID.randomUUID();
        UUID driverUserId = UUID.randomUUID();
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("driverId", driverId.toString());
        candidate.put("driverUserId", driverUserId.toString());
        candidate.put("distanceM", 400);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("candidates", List.of(candidate));
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), "DriverProposed", 1, Instant.now(), "matching-service",
                UUID.randomUUID(), null, tripId, null, payload);
        new TransactionTemplate(txManager).executeWithoutResult(s -> inboxHandler.handle(env));
        assertThat(tripState(tripId)).isEqualTo("DRIVER_OFFERED");
        UUID reservationId = jdbc.queryForObject("SELECT reservation_id FROM trips WHERE id = ?", UUID.class, tripId);
        return new Offered(tripId, passengerUserId, driverId, driverUserId, reservationId, attempt);
    }

    Offered offeredTrip() throws Exception {
        UUID passenger = UUID.randomUUID();
        return offer(createTrip(passenger), passenger);
    }

    // ---------- đọc DB ----------

    String tripState(UUID tripId) {
        return jdbc.queryForObject("SELECT state FROM trips WHERE id = ?", String.class, tripId);
    }

    int tripInt(UUID tripId, String column) {
        Integer v = jdbc.queryForObject("SELECT " + column + " FROM trips WHERE id = ?", Integer.class, tripId);
        return v == null ? 0 : v;
    }

    String sagaPhase(UUID tripId) {
        return jdbc.queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> sagaContext(UUID tripId) throws Exception {
        String ctx = jdbc.queryForObject("SELECT context::text FROM sagas WHERE trip_id = ?", String.class, tripId);
        return JSON.readValue(ctx.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    List<String> tripEventTypes(UUID tripId) {
        return jdbc.queryForList("SELECT type FROM trip_events WHERE trip_id = ? ORDER BY id", String.class, tripId);
    }
}
