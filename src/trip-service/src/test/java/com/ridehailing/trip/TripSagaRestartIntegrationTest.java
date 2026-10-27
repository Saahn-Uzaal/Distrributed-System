package com.ridehailing.trip;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.ridehailing.trip.event.EventEnvelope;
import com.ridehailing.trip.service.SagaRecoveryWorker;
import com.ridehailing.trip.service.TripInboxHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T21 — restart Saga (spec §10 "Khởi động lại"): HAI application context thật trên cùng một PostgreSQL,
 * KHÔNG có RabbitMQ (broker cố tình vắng mặt — outbox giữ PENDING + warn log, hành vi đã verify ở T13).
 * <ul>
 *   <li>Kịch bản A: ctx1 để lại intent CONFIRMING treo (accept timeout) rồi đóng; worker của ctx2
 *       resume idempotent → ACCEPTED.</li>
 *   <li>Kịch bản B: ctx2 để lại intent COMPENSATING/REJECT treo; worker ctx3 sau restart hoàn tất tìm lại
 *       attempt+1, outbox TripRequested attempt 2 giữ PENDING (broker down).</li>
 * </ul>
 */
@Testcontainers
class TripSagaRestartIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withUsername("trip_restart").withPassword("trip_restart_pw");

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static final StatefulDriverStub DRIVER = new StatefulDriverStub();
    /** Cổng AMQP "chết" cố tình — broker vắng mặt để outbox giữ PENDING (T13 acceptance). */
    private static final int DEAD_AMQP_PORT = freeLocalPort();
    private static final Path PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        try {
            Path dir = Files.createTempDirectory("jwt-restart-keys-trip");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = dir.resolve("public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub;
            SIGNER = new RSASSASigner(pair.getPrivate());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void resetDriver() {
        DRIVER.reset();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanTables() {
        for (String table : List.of("inbox_events", "sagas", "trip_events", "outbox", "idempotency_keys", "trips")) {
            jdbc().update("DELETE FROM " + table);
        }
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword()));
        }
        return jdbc;
    }

    static int freeLocalPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Khởi động trip-service thật: DB chung, stub driver, broker vắng mặt; interval worker theo tham số. */
    private ConfigurableApplicationContext start(long workerIntervalMs) {
        return new SpringApplication(TripServiceApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--jwt.public-key-path=" + PUBLIC_KEY_PATH,
                "--app.trip.internal-api-token=restart-test-token",
                "--spring.rabbitmq.host=127.0.0.1",
                "--spring.rabbitmq.port=" + DEAD_AMQP_PORT,
                "--spring.rabbitmq.password=restart-rabbit-dummy",
                "--app.trip.driver-service-url=http://localhost:" + DRIVER.port(),
                "--app.trip.http-timeout-ms=800",
                "--app.saga.worker-interval-ms=" + workerIntervalMs);
    }

    private String token(UUID userId, String role) throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("email", role.toLowerCase() + "-restart@example.com");
        claims.put("role", role);
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of("trip-service"));
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jws.sign(SIGNER);
        return jws.serialize();
    }

    private record Resp(int status, Map<String, Object> body) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return (Map<String, Object>) JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    private UUID createTrip(ConfigurableApplicationContext ctx, UUID passengerUserId) throws Exception {
        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/trips"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token(passengerUserId, "PASSENGER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                                + "\"destination\":{\"latitude\":10.8,\"longitude\":106.72}}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(202);
        return UUID.fromString((String) parse(resp.body()).get("tripId"));
    }

    /** DriverProposed (1 candidate) qua handler T16 của context → DRIVER_OFFERED. */
    private record Offered(UUID tripId, UUID driverId, UUID driverUserId, UUID reservationId, int attempt) {
    }

    private Offered offer(ConfigurableApplicationContext ctx, UUID tripId) {
        TripInboxHandler handler = ctx.getBean(TripInboxHandler.class);
        TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        int attempt = jdbc().queryForObject("SELECT matching_attempt FROM trips WHERE id = ?", Integer.class, tripId);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("driverId", UUID.randomUUID().toString());
        candidate.put("driverUserId", UUID.randomUUID().toString());
        candidate.put("distanceM", 400);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("candidates", List.of(candidate));
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), "DriverProposed", 1, Instant.now(), "matching-service",
                UUID.randomUUID(), null, tripId, null, payload);
        tx.executeWithoutResult(s -> handler.handle(env));

        Map<String, Object> row = jdbc().queryForMap(
                "SELECT driver_id, driver_user_id, reservation_id, matching_attempt FROM trips WHERE id = ?", tripId);
        assertThat(jdbc().queryForObject("SELECT state FROM trips WHERE id = ?", String.class, tripId))
                .isEqualTo("DRIVER_OFFERED");
        return new Offered(tripId, (UUID) row.get("driver_id"), (UUID) row.get("driver_user_id"),
                (UUID) row.get("reservation_id"), ((Number) row.get("matching_attempt")).intValue());
    }

    private Resp accept(ConfigurableApplicationContext ctx, UUID tripId, UUID driverUserId, int attempt) throws Exception {
        return command(ctx, "/api/trips/" + tripId + "/accept", token(driverUserId, "DRIVER"), "{\"attempt\":" + attempt + "}");
    }

    private Resp reject(ConfigurableApplicationContext ctx, UUID tripId, UUID driverUserId, int attempt) throws Exception {
        return command(ctx, "/api/trips/" + tripId + "/reject", token(driverUserId, "DRIVER"), "{\"attempt\":" + attempt + "}");
    }

    private Resp command(ConfigurableApplicationContext ctx, String path, String bearer, String jsonBody) throws Exception {
        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    /** Poll state + gọi tường minh {@code processDueSagas()} mỗi tick — deterministic khi worker
     *  schedule tắt (interval dài); cùng code path với tick production. */
    private void pollWithWorker(SagaRecoveryWorker worker, String state, UUID tripId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!state.equals(jdbc().queryForObject("SELECT state FROM trips WHERE id = ?", String.class, tripId))
                && System.nanoTime() < deadline) {
            worker.processDueSagas();
            Thread.sleep(100);
        }
    }

    @Test
    void restartResumesPendingSagaIntents() throws Exception {
        // ---- Kịch bản A: intent CONFIRMING treo sống qua restart ----
        UUID tripId;
        UUID tripId2;
        Offered o2;
        String reservationA;
        try (ConfigurableApplicationContext ctx1 = start(60_000)) { // worker ctx1 không kịp chạy trước khi đóng
            UUID passenger = UUID.randomUUID();
            tripId = createTrip(ctx1, passenger);
            Offered o = offer(ctx1, tripId);
            reservationA = o.reservationId().toString();

            DRIVER.delayBeforeApplyCount.put("confirm", 1);
            DRIVER.failGet = 1;
            Resp pending = accept(ctx1, tripId, o.driverUserId(), o.attempt());
            assertThat(pending.status()).isEqualTo(202);
            assertThat(pending.body()).containsEntry("state", "DRIVER_OFFERED");
            assertThat(jdbc().queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId))
                    .isEqualTo("CONFIRMING");
        } // đóng ctx1 — intent vẫn nằm trong DB

        // ctx2: interval worker dài (60 s) → schedule không chạy trong test; resume qua
        // processDueSagas() tường minh — deterministic, cùng code path với tick production.
        try (ConfigurableApplicationContext ctx2 = start(60_000)) {
            SagaRecoveryWorker worker2 = ctx2.getBean(SagaRecoveryWorker.class);
            pollWithWorker(worker2, "ACCEPTED", tripId);
            assertThat(jdbc().queryForObject("SELECT state FROM trips WHERE id = ?", String.class, tripId))
                    .isEqualTo("ACCEPTED");
            StatefulDriverStub.Reservation res = DRIVER.reservation(reservationA);
            assertThat(res.state).isEqualTo("CONFIRMED");
            // Lần confirm 503 không được parse → chỉ một body confirm thành công (commandId tất định).
            assertThat(DRIVER.commandBodies).hasSize(1);
            assertThat(jdbc().queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId))
                    .isEqualTo("AWAITING_FINISH");

            // ---- Kịch bản B: ctx2 để intent COMPENSATING/REJECT, ctx3 phục hồi sau restart ----
            UUID passenger2 = UUID.randomUUID();
            tripId2 = createTrip(ctx2, passenger2);
            o2 = offer(ctx2, tripId2);

            DRIVER.delayBeforeApplyCount.put("release", 1);
            DRIVER.failGet = 1;
            Resp pending2 = reject(ctx2, tripId2, o2.driverUserId(), o2.attempt());
            assertThat(pending2.status()).isEqualTo(202);
            assertThat(pending2.body()).containsEntry("state", "DRIVER_OFFERED");
            assertThat(jdbc().queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId2))
                    .isEqualTo("COMPENSATING");
        } // The pending REJECT must survive an actual application-context restart too.

        try (ConfigurableApplicationContext ctx3 = start(60_000)) {
            // Fault đã được request tiêu thụ (503 + GET timeout); xóa tường minh cho rõ.
            DRIVER.delayBeforeApplyCount.remove("release");
            DRIVER.failGet = 0;
            pollWithWorker(ctx3.getBean(SagaRecoveryWorker.class), "SEARCHING", tripId2);
            assertThat(jdbc().queryForObject("SELECT matching_attempt FROM trips WHERE id = ?", Integer.class, tripId2))
                    .isEqualTo(2);
            StatefulDriverStub.Reservation res2 = DRIVER.reservation(o2.reservationId().toString());
            assertThat(res2.state).isEqualTo("RELEASED");
            assertThat(res2.terminalReason).isEqualTo("REJECTED");
            assertThat(jdbc().queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId2))
                    .isEqualTo("WAITING_MATCH");
            Map<String, Object> drivers = jdbc().queryForMap(
                    "SELECT driver_id, driver_user_id, reservation_id FROM trips WHERE id = ?", tripId2);
            assertThat(drivers.values()).containsOnlyNulls();

            // Broker down → mọi TripRequested giữ PENDING (T13 acceptance): attempt 1 từ tạo trip
            // + attempt 2 từ rematch sau reject (excludedDriverIds = driver bị reject).
            List<Map<String, Object>> rows = jdbc().queryForList(
                    "SELECT payload::text AS payload, status FROM outbox WHERE aggregate_id = ? AND event_type = 'TripRequested'"
                            + " ORDER BY created_at, id", tripId2);
            assertThat(rows).hasSize(2);
            List<Integer> attempts = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                assertThat(row.get("status")).isEqualTo("PENDING");
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) parse((String) row.get("payload"));
                attempts.add(((Number) p.get("attempt")).intValue());
            }
            assertThat(attempts).containsExactlyInAnyOrder(1, 2);
        }
    }

    @Test
    void restartResumesExpiryAndDeadlineCompensation() throws Exception {
        Offered expired;
        UUID deadlineTrip;
        try (ConfigurableApplicationContext ctx1 = start(3_600_000)) {
            var commands = ctx1.getBean(com.ridehailing.trip.service.TripCommandService.class);
            expired = offer(ctx1, createTrip(ctx1, UUID.randomUUID()));
            jdbc().update("UPDATE sagas SET context = jsonb_set(context, '{offerExpiresAt}', ?::jsonb) WHERE trip_id = ?",
                    "\"" + Instant.now().minusSeconds(1) + "\"", expired.tripId());
            DRIVER.delayBeforeApplyCount.put("release", 1);
            DRIVER.failGet = 1;
            commands.handleOfferExpired(expired.tripId());

            deadlineTrip = createTrip(ctx1, UUID.randomUUID());
            jdbc().update("UPDATE sagas SET deadline_at = clock_timestamp() - interval '1 second' WHERE trip_id = ?", deadlineTrip);
            DRIVER.delayBeforeApplyCount.put("release", 1);
            DRIVER.failGet = 1;
            commands.handleWaitingMatchDeadline(deadlineTrip);
            for (UUID id : List.of(expired.tripId(), deadlineTrip)) {
                assertThat(jdbc().queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, id))
                        .isEqualTo("COMPENSATING");
            }
        }

        DRIVER.delayBeforeApplyCount.clear();
        DRIVER.failGet = 0;
        try (ConfigurableApplicationContext ctx2 = start(3_600_000)) {
            var worker = ctx2.getBean(SagaRecoveryWorker.class);
            pollWithWorker(worker, "SEARCHING", expired.tripId());
            pollWithWorker(worker, "NO_DRIVER", deadlineTrip);
            assertThat(jdbc().queryForObject("SELECT state FROM trips WHERE id = ?", String.class, expired.tripId()))
                    .isEqualTo("SEARCHING");
            assertThat(DRIVER.reservation(expired.reservationId().toString()).terminalReason).isEqualTo("OFFER_EXPIRED");
            assertThat(jdbc().queryForObject("SELECT state FROM trips WHERE id = ?", String.class, deadlineTrip))
                    .isEqualTo("NO_DRIVER");
            for (int i = 0; i < 5; i++) {
                assertThat(DRIVER.reservation(TripInboxHandler.deterministicReservationId(deadlineTrip, 1, i).toString()).state)
                        .isEqualTo("RELEASED");
            }
        }
    }
}
