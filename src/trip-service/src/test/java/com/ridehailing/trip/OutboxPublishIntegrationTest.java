package com.ridehailing.trip;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ridehailing.trip.repo.OutboxRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 — outbox publish integration test trên HTTP thật + PostgreSQL thật + RabbitMQ thật (Testcontainers).
 * Acceptance spec §20.4: Trip và outbox commit cùng nhau (T12); broker down vẫn giữ pending;
 * broker lại lên phát được; rollback không phát nghiệp vụ (outbox chỉ ghi trong transaction T12).
 * events.md §1/§4.6: persistent + mandatory + confirms; returned → giữ PENDING + last_error;
 * eventId ổn định qua mọi lần publish (§2).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Đóng context (và scheduler outbox) khi xong class, không để worker chạy tiếp khi container đã dừng.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OutboxPublishIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("trip_test")
            .withPassword("trip_test_pw");

    /** Cổng host CỐ ĐỊNH cho AMQP: Testcontainers mặc định gán cổng host ngẫu nhiên, và sau
     *  docker stop/start Docker cấp lại cổng KHÁC -> app (đã cấu hình cổng cũ) không bao giờ kết nối
     *  lại được. Cố định cổng giúp test "broker down rồi lên lại" đúng nghĩa. */
    private static final int AMQP_HOST_PORT = freeLocalPort();

    // F32: pin đúng patch khớp compose (rabbitmq:3.13.7-management-alpine).
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:3.13.7-management-alpine"))
            .withAdminUser("ridehailing_dev")
            .withAdminPassword("rabbit_dev_2026")
            // Chỉ expose AMQP: Testcontainers chờ MỌI cổng exposed có ánh xạ, mà bên dưới chỉ bind 5672.
            .withExposedPorts(5672)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", AMQP_HOST_PORT),
                            new com.github.dockerjava.api.model.ExposedPort(5672))));

    private static int freeLocalPort() {
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-trip-t13");
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
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(AMQP_HOST_PORT));
        registry.add("spring.rabbitmq.username", () -> "ridehailing_dev");
        registry.add("spring.rabbitmq.password", () -> "rabbit_dev_2026");
        // Scheduler chạy thưa để các test tự gọi worker và kiểm soát thời điểm; vẫn đủ cho test broker down/up.
        registry.add("app.outbox.publish-interval-ms", () -> "1000");
        // T16: token internal API driver-service (N09) — context đầy đủ cần property này
        // dù test không gọi HTTP driver-service.
        registry.add("app.trip.internal-api-token", () -> "test_internal_token_2026");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxRepository outboxRepo;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private com.ridehailing.trip.service.OutboxPublishWorker worker;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    record Resp(int status, Map<String, Object> body) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM trip_events");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM trips");
    }

    // --- helpers ---

    /** JWT RS256 test — claims đúng contract §2 (pattern T12). */
    private static String token(UUID userId, String role) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("email", role.toLowerCase() + "-t13@example.com");
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

    private static final String QUOTE_BODY = "{\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
            + "\"destination\":{\"latitude\":10.8000,\"longitude\":106.7200}}";

    /** Mô phỏng topology T04 phía consumer: exchange ride.events + queue matching.inbox bind trip.requested. */
    private void declareMatchingInbox() throws Exception {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare("ride.events", "topic", true);
            channel.queueDeclare("matching.inbox", true, false, false, null);
            channel.queueBind("matching.inbox", "ride.events", "trip.requested");
            return null;
        });
    }

    private UUID eventIdForTrip(String tripId) {
        return jdbc.queryForObject(
                "SELECT id FROM outbox WHERE aggregate_id = ? AND event_type = 'TripRequested'",
                UUID.class, UUID.fromString(tripId));
    }

    /** spring-amqp 4.x: message nhận từ broker có delivery mode ở getReceivedDeliveryMode()
     *  (getDeliveryMode() luôn null khi receive). */
    private static void assertPersistent(org.springframework.amqp.core.Message msg) {
        assertThat(msg.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    /** Queue matching.inbox được chia sẻ giữa các test trong cùng JVM — nhận message khớp eventId,
     *  bỏ qua message cũ còn sót từ test trước. */
    private org.springframework.amqp.core.Message receiveEvent(UUID expectedEventId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var msg = rabbitTemplate.receive("matching.inbox", 1_000);
            if (msg == null) {
                continue;
            }
            Map<String, Object> env = parse(new String(msg.getBody(), StandardCharsets.UTF_8));
            if (expectedEventId.toString().equals(env.get("eventId"))) {
                return msg;
            }
        }
        throw new AssertionError("no message with eventId " + expectedEventId + " in matching.inbox within 10 s");
    }

    /** Stop/start CÙNG container qua docker-java — KHÔNG dùng Testcontainers stop()/start()
     *  (stop() xóa container). Cổng host cố định (AMQP_HOST_PORT) nên sau start app kết nối lại đúng
     *  cổng cũ; topology (exchange/queue durable) giữ nguyên — đúng nghĩa "broker down/up". */
    private static void stopBrokerContainer(RabbitMQContainer rabbit) {
        rabbit.getDockerClient().stopContainerCmd(rabbit.getContainerId()).exec();
    }

    private static void startBrokerContainer(RabbitMQContainer rabbit) {
        rabbit.getDockerClient().startContainerCmd(rabbit.getContainerId()).exec();
    }

    /** Chờ broker sẵn sàng sau startBrokerContainer (container cần thời gian boot lại). */
    private void awaitBrokerReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                rabbitTemplate.execute(channel -> null);
                return;
            } catch (Exception e) {
                lastError = e;
                Thread.sleep(500);
            }
        }
        // Diagnostic: trạng thái container + log RabbitMQ gần nhất khi broker không sẵn sàng.
        String diag;
        try {
            var info = RABBIT.getDockerClient().inspectContainerCmd(RABBIT.getContainerId()).exec();
            diag = "container status=" + info.getState().getStatus()
                    + ", running=" + info.getState().getRunning()
                    + ", exitCode=" + info.getState().getExitCode();
            try {
                String logs = RABBIT.getLogs();
                diag += "; last logs: \n" + tailLines(logs, 25);
            } catch (Exception le) {
                diag += "; getLogs failed: " + le;
            }
        } catch (Exception ie) {
            diag = "inspect failed: " + ie;
        }
        throw new AssertionError("broker did not become ready within " + timeoutMs
                + " ms; last error: " + lastError + "; " + diag);
    }

    private static String tailLines(String s, int n) {
        if (s == null || s.isEmpty()) return "(empty)";
        String[] lines = s.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Đợi row outbox đạt status mong muốn (worker chu kỳ ~1 s). */
    private void awaitOutboxStatus(UUID eventId, String expectedStatus, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject("SELECT status FROM outbox WHERE id = ?", String.class, eventId);
            if (expectedStatus.equals(status)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("outbox row " + eventId + " did not reach status " + expectedStatus
                + " within " + timeoutMs + " ms");
    }

    // --- tests ---

    /** Create trip → worker publish TripRequested persistent vào matching.inbox; envelope chuẩn §2,
     *  eventId = outbox.id ổn định, correlationId giữ nguyên từ header request. */
    @org.junit.jupiter.api.Test
    void createTripPublishesStableEnvelopeToMatchingInbox() throws Exception {
        declareMatchingInbox();

        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        UUID key = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        Resp resp = send("POST", "/api/trips", token,
                Map.of("Idempotency-Key", key.toString(), "X-Correlation-ID", correlation.toString()), QUOTE_BODY);
        assertThat(resp.status()).isEqualTo(202);
        String tripId = (String) resp.body().get("tripId");

        UUID eventId = eventIdForTrip(tripId);
        awaitOutboxStatus(eventId, "SENT", 15_000);

        var msg = receiveEvent(eventId);
        assertPersistent(msg);

        Map<String, Object> env = parse(new String(msg.getBody(), StandardCharsets.UTF_8));
        assertThat(env.get("eventId")).isEqualTo(eventId.toString()); // eventId ổn định = outbox.id (§2)
        assertThat(env.get("eventType")).isEqualTo("TripRequested");
        assertThat(((Number) env.get("schemaVersion")).intValue()).isEqualTo(1);
        assertThat(env.get("producer")).isEqualTo("trip-service");
        assertThat(env.get("correlationId")).isEqualTo(correlation.toString()); // giữ nguyên xuyên luồng (§5)
        assertThat(env.get("aggregateId")).isEqualTo(tripId);
        assertThat(((Number) env.get("aggregateVersion")).intValue()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload.get("tripId")).isEqualTo(tripId);
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> pickup = (Map<String, Object>) payload.get("pickup");
        assertThat(((Number) pickup.get("latitude")).doubleValue()).isEqualTo(10.7769);
        assertThat(((Number) pickup.get("longitude")).doubleValue()).isEqualTo(106.7009);
        assertThat(payload.get("excludedDriverIds")).isEqualTo(List.of());

        // sent_at được ghi khi đánh dấu SENT.
        Object sentAt = jdbc.queryForObject("SELECT sent_at FROM outbox WHERE id = ?", Object.class, eventId);
        assertThat(sentAt).isNotNull();
    }

    /** Broker down: row giữ PENDING + last_error; broker lên lại → publish thành công với CÙNG eventId (§2). */
    @org.junit.jupiter.api.Test
    void brokerDownKeepsPendingThenPublishesWhenBrokerReturns() throws Exception {
        declareMatchingInbox(); // declare khi broker up (topology persistent trong container — giữ qua stop/start)

        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");

        stopBrokerContainer(RABBIT);
        UUID eventId;
        try {
            Resp resp = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
            assertThat(resp.status()).isEqualTo(202); // app vẫn chạy khi broker down (T13 acceptance)
            String tripId = (String) resp.body().get("tripId");
            eventId = eventIdForTrip(tripId);

            // Worker retry chu kỳ kế tiếp: row giữ PENDING, last_error được ghi.
            Thread.sleep(4_000);
            Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM outbox WHERE id = ?", eventId);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat((String) row.get("last_error")).isNotBlank();
        } finally {
            startBrokerContainer(RABBIT); // broker lại lên — CÙNG container, port mapping + topology giữ nguyên
        }

        // RabbitMQ boot ~10-25 s sau docker start → poll cho tới khi connect được.
        awaitBrokerReady(90_000);
        // Backoff tối đa 30 s → cửa sổ chờ SENT phải rộng hơn.
        awaitOutboxStatus(eventId, "SENT", 45_000);

        var msg = receiveEvent(eventId);
        Map<String, Object> env = parse(new String(msg.getBody(), StandardCharsets.UTF_8));
        // eventId ổn định qua lần publish sau khi broker trở lại (§2) — không sinh lại.
        UUID deliveredEventId = UUID.fromString((String) env.get("eventId"));
        assertThat(deliveredEventId).isEqualTo(eventId);
    }

    /** Message bị broker returned (routing key không có binding) → giữ PENDING + last_error, KHÔNG đánh dấu SENT (§1). */
    @org.junit.jupiter.api.Test
    void returnedMessageKeepsPendingWithLastError() throws Exception {
        declareMatchingInbox(); // exchange tồn tại; routing key bên dưới không có binding

        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String payloadJson = "{\"tripId\":\"" + tripId + "\",\"attempt\":1,"
                + "\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},\"excludedDriverIds\":[]}";
        outboxRepo.insertPending(eventId, "TripRequested", tripId, payloadJson, correlation,
                Instant.now().truncatedTo(ChronoUnit.SECONDS), 1, "no.such.binding");

        long deadline = System.currentTimeMillis() + 15_000;
        Map<String, Object> row = null;
        while (System.currentTimeMillis() < deadline) {
            row = jdbc.queryForMap("SELECT status, last_error FROM outbox WHERE id = ?", eventId);
            if ("PENDING".equals(row.get("status")) && row.get("last_error") != null) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(row.get("status")).isEqualTo("PENDING"); // không bao giờ coi returned là đã gửi (§1)
        assertThat((String) row.get("last_error")).contains("returned");
    }

    // --- sửa sau review T13 ---

    private UUID insertPending(String routingKey, String payloadJson) {
        UUID eventId = UUID.randomUUID();
        outboxRepo.insertPending(eventId, "TripRequested", UUID.randomUUID(), payloadJson, UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS), 1, routingKey);
        return eventId;
    }

    private static String validPayload() {
        return "{\"tripId\":\"" + UUID.randomUUID() + "\",\"attempt\":1,"
                + "\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},\"excludedDriverIds\":[]}";
    }

    /** Payload không phải object (envelope không dựng được) -> row đó giữ PENDING + last_error,
     *  row hợp lệ phía sau vẫn được publish (không chặn đầu hàng đợi). */
    @org.junit.jupiter.api.Test
    void poisonPayloadDoesNotBlockLaterRows() throws Exception {
        declareMatchingInbox();
        UUID poison = insertPending("trip.requested", "[1,2,3]");
        Thread.sleep(20); // created_at sau poison -> đứng sau trong FIFO
        UUID good = insertPending("trip.requested", validPayload());

        awaitOutboxStatus(good, "SENT", 15_000);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM outbox WHERE id = ?", poison);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((String) row.get("last_error")).contains("envelope build failed");
        receiveEvent(good);
    }

    /** Hai worker chạy song song (cộng với scheduler) không publish trùng một row:
     *  claim FOR UPDATE SKIP LOCKED + lease. Mỗi eventId đến queue đúng một lần, AMQP messageId = eventId. */
    @org.junit.jupiter.api.Test
    void concurrentWorkersPublishEachRowExactlyOnce() throws Exception {
        declareMatchingInbox();
        rabbitTemplate.execute(channel -> { channel.queuePurge("matching.inbox"); return null; });
        java.util.Set<String> expected = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            expected.add(insertPending("trip.requested", validPayload()).toString());
        }

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                barrier.await();
                for (int round = 0; round < 3; round++) {
                    worker.publishPending();
                }
                return null;
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        for (String id : expected) {
            awaitOutboxStatus(UUID.fromString(id), "SENT", 15_000);
        }

        Map<String, Integer> seen = new java.util.HashMap<>();
        while (true) {
            var msg = rabbitTemplate.receive("matching.inbox", 1_000);
            if (msg == null) {
                break;
            }
            Map<String, Object> env = parse(new String(msg.getBody(), StandardCharsets.UTF_8));
            String eventId = (String) env.get("eventId");
            if (expected.contains(eventId)) {
                seen.merge(eventId, 1, Integer::sum);
                assertThat(msg.getMessageProperties().getMessageId()).isEqualTo(eventId);
            }
        }
        assertThat(seen.keySet()).isEqualTo(expected);
        assertThat(seen.values()).allMatch(count -> count == 1);
    }

    /** Transaction create bị rollback (409 ACTIVE_TRIP_EXISTS) -> không có row outbox -> không phát event. */
    @org.junit.jupiter.api.Test
    void rolledBackCreateDoesNotPublish() throws Exception {
        declareMatchingInbox();
        UUID passenger = UUID.randomUUID();
        String token = token(passenger, "PASSENGER");
        Resp first = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        assertThat(first.status()).isEqualTo(202);
        Resp second = send("POST", "/api/trips", token, Map.of("Idempotency-Key", UUID.randomUUID().toString()), QUOTE_BODY);
        assertThat(second.status()).isEqualTo(409);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox o JOIN trips t ON t.id = o.aggregate_id WHERE t.passenger_id = ?",
                Integer.class, passenger);
        assertThat(rows).isEqualTo(1);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM trips WHERE passenger_id = ?", Integer.class, passenger);
        assertThat(total).isEqualTo(1);
    }
}
