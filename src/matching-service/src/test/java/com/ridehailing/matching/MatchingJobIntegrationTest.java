package com.ridehailing.matching;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.ridehailing.matching.config.RabbitTopologyConfig;
import com.ridehailing.matching.inbox.DlqRepublisher;
import com.ridehailing.matching.inbox.MatchingInboxListener;
import com.ridehailing.matching.service.MatchingJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T15 — matching job thật (spec §20.4) trên PostgreSQL thật + RabbitMQ thật (Testcontainers):
 * consume TripRequested → lưu job/attempt → gọi candidates driver-service (stub HTTP loopback)
 * → bỏ excluded drivers → Matching outbox phát DriverProposed/NoDriverFound. **Không tự reserve
 * driver** — trip-service nhận proposal và gọi reserve ở T16.
 *
 * <p>Acceptance spec §20.4: "Driver gần được xếp đầu; không có driver phát đúng event; replay
 * không tạo job trùng; API Driver timeout đi nhánh retry." events.md §2/§3.2/§3.3/§4/§5 là contract.
 */
@SpringBootTest(classes = MatchingServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
// Đóng context (listener container + outbox worker) khi xong class, không để consumer chạy tiếp
// khi container đã dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class MatchingJobIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("matching_test")
            .withPassword("matching_test_pw");

    /** Cổng host CỐ ĐỊNH cho AMQP (pattern T13/T14): Testcontainers mặc định gán cổng host ngẫu nhiên;
     *  cố định giúp app cấu hình một lần và kết nối lại đúng cổng nếu broker restart. */
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
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", AMQP_HOST_PORT),
                            new ExposedPort(5672))));

    /** Stub internal candidates API driver-service (contract §4.2) — JDK HttpServer trên loopback,
     *  KHÔNG thêm dependency; RestClient gọi HTTP thật qua loopback. Khởi động trước Spring context
     *  (static init) để DynamicPropertySource đọc được port. */
    static final DriverStub DRIVER_STUB = new DriverStub();

    private static int freeLocalPort() {
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
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(AMQP_HOST_PORT));
        registry.add("spring.rabbitmq.username", () -> "ridehailing_dev");
        registry.add("spring.rabbitmq.password", () -> "rabbit_dev_2026");
        // Backoff ngắn cho test (mặc định production 1s/3s/10s).
        registry.add("app.inbox.retry-backoff-ms", () -> "300,700,1500");
        // T15: stub driver-service + token internal (N08/N09: không có default trong yml).
        registry.add("app.matching.driver-service-url", () -> "http://localhost:" + DRIVER_STUB.port());
        registry.add("app.matching.internal-api-token", () -> "test_internal_token_2026");
        // Read timeout 800 ms < delay stub 2000 ms → nhánh timeout thật của RestClient.
        registry.add("app.matching.http-timeout-ms", () -> "800");
        // Outbox worker chạy nhanh trong test (mặc định production 1000 ms).
        registry.add("app.outbox.publish-interval-ms", () -> "200");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Queue trip-service declare ở T16 (bind `matching.#`) — test mô phỏng topology đó để outbox
     *  publish mandatory=true không bị broker returned. */
    private static final String TRIP_INBOX_QUEUE = "trip.inbox";

    // ---------- stub driver-service ----------

    /** Stub GET /internal/drivers/candidates: responseJson/delayMs/timeoutCallsRemaining đổi được
     *  theo từng test. timeoutCallsRemaining>0 → N lần gọi đầu sleep(delayMs) → client read-timeout. */
    static final class DriverStub {
        private final HttpServer server;
        volatile String responseJson = "{\"candidates\":[]}";
        volatile long delayMs = 0;
        final AtomicInteger timeoutCallsRemaining = new AtomicInteger(0);
        /** Query string (raw) của lần gọi gần nhất — kiểm chứng excludeDriverIds được gửi (M1). */
        volatile String lastQuery;
        volatile String lastCorrelationId;

        DriverStub() {
            try {
                // JDK 21: create(addr, backlog) — executor mặc định xử lý tuần tự. Test 5 chọn
                // delayMs (900 ms) < read-timeout client (800 ms) + backoff đầu (300 ms) để handler
                // call 1 kịp kết thúc trước khi call 2 đến → chỉ call 1 timeout, call 2 thành công.
                this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                this.server.createContext("/internal/drivers/candidates", exchange -> {
                    lastQuery = exchange.getRequestURI().getRawQuery();
                    lastCorrelationId = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                    if (delayMs > 0 && timeoutCallsRemaining.decrementAndGet() >= 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
                this.server.start();
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        void reset() {
            responseJson = "{\"candidates\":[]}";
            delayMs = 0;
            timeoutCallsRemaining.set(0);
            lastQuery = null;
            lastCorrelationId = null;
        }
    }

    record StubCandidate(UUID driverId, UUID driverUserId, long distanceM) {
    }

    private static String candidatesJson(StubCandidate... cands) {
        StringBuilder sb = new StringBuilder("{\"candidates\":[");
        for (int i = 0; i < cands.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            StubCandidate c = cands[i];
            sb.append("{\"driverId\":\"").append(c.driverId())
                    .append("\",\"driverUserId\":\"").append(c.driverUserId())
                    .append("\",\"distanceM\":").append(c.distanceM()).append('}');
        }
        return sb.append("]}").toString();
    }

    // ---------- setup/cleanup ----------

    @BeforeEach
    void resetState() {
        DRIVER_STUB.reset();
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM matching_jobs");
        jdbc.update("DELETE FROM outbox");
        declareTripInbox();
        // spring-rabbit 4.x/amqp-client 5: RabbitTemplate không còn purgeQueue, Channel dùng queuePurge.
        rabbitTemplate.execute(channel -> channel.queuePurge(TRIP_INBOX_QUEUE)); // không lẫn message test trước
    }

    /** Mô phỏng topology T16 (test-only): trip-service declare trip.inbox bind `matching.#`.
     *  Không có binding này, outbox publish mandatory=true bị broker returned → PENDING mãi. */
    private void declareTripInbox() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(RabbitTopologyConfig.EVENTS_EXCHANGE, "topic", true);
            channel.queueDeclare(TRIP_INBOX_QUEUE, true, false, false, null);
            channel.queueBind(TRIP_INBOX_QUEUE, RabbitTopologyConfig.EVENTS_EXCHANGE, "matching.#");
            return null;
        });
    }

    // ---------- DB helpers ----------

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private int jobCount(UUID tripId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching_jobs WHERE trip_id = ?", Integer.class, tripId);
        return n == null ? 0 : n;
    }

    private String jobState(UUID tripId, int attempt) {
        return jdbc.queryForObject("SELECT state FROM matching_jobs WHERE trip_id = ? AND attempt = ?",
                String.class, tripId, attempt);
    }

    /** candidates JSONB của job (list có thứ tự) — null khi NO_DRIVER_FOUND. */
    private List<Map<String, Object>> candidatesOfJob(UUID tripId, int attempt) throws Exception {
        String json = jdbc.queryForObject(
                "SELECT candidates FROM matching_jobs WHERE trip_id = ? AND attempt = ?",
                String.class, tripId, attempt);
        if (json == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = JSON.readValue(json.getBytes(StandardCharsets.UTF_8), List.class);
        return list;
    }

    private int outboxCount(UUID tripId, UUID causationEventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, causationEventId);
        return n == null ? 0 : n;
    }

    // ---------- publish/receive helpers (envelope theo events.md §2/§3.1) ----------

    private MessageProperties baseProps(UUID eventId, UUID correlationId, String eventType) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());          // messageId = eventId (§1: consumer dedup ở tầng AMQP)
        props.setCorrelationId(correlationId.toString());
        props.setType(eventType);
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return props;
    }

    private byte[] envelopeBytes(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                List<UUID> excluded) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", "TripRequested");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "trip-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("pickup", Map.of("latitude", 10.7769, "longitude", 106.7009));
        payload.put("excludedDriverIds", excluded.stream().map(UUID::toString).toList());
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish TripRequested qua exchange ride.events rk trip.requested (đường đi thật của T13). */
    private void publishTripRequested(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                     List<UUID> excluded) throws Exception {
        byte[] body = envelopeBytes(eventId, correlationId, tripId, attempt, excluded);
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_REQUESTED_ROUTING_KEY,
                new Message(body, baseProps(eventId, correlationId, "TripRequested")));
    }

    private void waitForInboxRow(UUID eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (inboxCount(eventId) >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no inbox row for eventId " + eventId + " within 30 s");
    }

    /** Chờ outbox matching-service của trip (causation = eventId TripRequested) thành SENT; trả id. */
    private UUID waitForOutboxSent(UUID tripId, UUID causationEventId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM outbox WHERE aggregate_id = ? AND causation_id = ? AND status = 'SENT'",
                    (rs, i) -> rs.getObject("id", UUID.class), tripId, causationEventId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no SENT outbox row for trip " + tripId
                + " (causation " + causationEventId + ") within 30 s");
    }

    /** Nhận message từ trip.inbox khớp messageId; timeout 30 s. */
    private Message receiveFromTripInbox(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(TRIP_INBOX_QUEUE, 1_000);
            if (msg == null) {
                continue;
            }
            if (messageId.equals(msg.getMessageProperties().getMessageId())) {
                return msg;
            }
        }
        throw new AssertionError("no trip.inbox message with messageId " + messageId + " within 30 s");
    }

    /** Nhận message từ matching.dlq khớp messageId; timeout 30 s (đủ cho backoff test 300/700/1500 ms). */
    private Message receiveFromDlq(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.MATCHING_DLQ_QUEUE, 1_000);
            if (msg == null) {
                continue;
            }
            if (messageId.equals(msg.getMessageProperties().getMessageId())) {
                return msg;
            }
        }
        throw new AssertionError("no DLQ message with messageId " + messageId + " within 30 s");
    }

    /** Assert không có message DLQ nào mang messageId này trong 5 s. */
    private void assertNoDlqMessage(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.MATCHING_DLQ_QUEUE, 1_000);
            if (msg != null && messageId.equals(msg.getMessageProperties().getMessageId())) {
                throw new AssertionError("unexpected DLQ message with messageId " + messageId);
            }
        }
    }

    /** spring-amqp 4.x: delivery mode của message nhận từ broker ở getReceivedDeliveryMode(). */
    private void assertPersistent(Message msg) {
        assertThat(msg.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    // ---------- log assertions (structured JSON, spec §11) ----------

    /** Bắt log của CẢ listener (inbox_retry/inbox_dlq/...) và handler (stale_event_skipped/...). */
    private record LogCapture(ListAppender<ILoggingEvent> listenerAppender,
                             ListAppender<ILoggingEvent> handlerAppender) {
        long count(String correlationId, String eventType, String result) {
            return countIn(listenerAppender, correlationId, eventType, result)
                    + countIn(handlerAppender, correlationId, eventType, result);
        }

        private static long countIn(ListAppender<ILoggingEvent> appender, String correlationId,
                                   String eventType, String result) {
            return new ArrayList<>(appender.list).stream().filter(e ->
                    correlationId.equals(e.getMDCPropertyMap().get("correlationId"))
                            && eventType.equals(e.getMDCPropertyMap().get("eventType"))
                            && result.equals(e.getMDCPropertyMap().get("result"))).count();
        }
    }

    private LogCapture attachLogCapture() {
        ListAppender<ILoggingEvent> listener = new ListAppender<>();
        listener.start();
        ((Logger) LoggerFactory.getLogger(MatchingInboxListener.class)).addAppender(listener);
        ListAppender<ILoggingEvent> handler = new ListAppender<>();
        handler.start();
        ((Logger) LoggerFactory.getLogger(MatchingJobHandler.class)).addAppender(handler);
        return new LogCapture(listener, handler);
    }

    private void detachLogCapture(LogCapture capture) {
        ((Logger) LoggerFactory.getLogger(MatchingInboxListener.class)).detachAppender(capture.listenerAppender());
        ((Logger) LoggerFactory.getLogger(MatchingJobHandler.class)).detachAppender(capture.handlerAppender());
    }

    // ---------- tests ----------

    /** Acceptance 1: "Driver gần được xếp đầu" — candidates giữ thứ tự deterministic từ API
     *  (distanceM tăng); outbox phát DriverProposed persistent, causationId = eventId TripRequested (§5). */
    @org.junit.jupiter.api.Test
    void tripRequestedProducesRankedDriverProposed() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID driverA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID driverB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Stub trả A (412 m) trước B (987 m) — thứ tự API deterministic, matching giữ nguyên.
        DRIVER_STUB.responseJson = candidatesJson(
                new StubCandidate(driverA, userA, 412L),
                new StubCandidate(driverB, userB, 987L));

        publishTripRequested(eventId, correlationId, tripId, 1, List.of());
        waitForInboxRow(eventId);

        // Contract §5: correlation của event được truyền sang HTTP gọi Driver.
        assertThat(DRIVER_STUB.lastCorrelationId).isEqualTo(correlationId.toString());

        // Job PROPOSED + candidates JSONB đúng thứ tự A→B.
        assertThat(jobState(tripId, 1)).isEqualTo("PROPOSED");
        List<Map<String, Object>> candidates = candidatesOfJob(tripId, 1);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).get("driverId")).isEqualTo(driverA.toString());
        assertThat(candidates.get(1).get("driverId")).isEqualTo(driverB.toString());

        // Outbox SENT → message thật trên trip.inbox (topology T16 mô phỏng trong test).
        UUID outboxEventId = waitForOutboxSent(tripId, eventId);
        Message msg = receiveFromTripInbox(outboxEventId.toString());
        assertThat(msg.getMessageProperties().getType()).isEqualTo("DriverProposed");
        assertThat(msg.getMessageProperties().getCorrelationId()).isEqualTo(correlationId.toString());
        assertPersistent(msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> env = JSON.readValue(msg.getBody(), Map.class);
        // §5: causationId = eventId của TripRequested; aggregateVersion optional — matching không
        // đọc Trip DB nên envelope KHÔNG chứa field này (chốt T15).
        assertThat(env.get("causationId")).isEqualTo(eventId.toString());
        assertThat(env).doesNotContainKey("aggregateVersion");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload.get("tripId")).isEqualTo(tripId.toString());
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentCandidates = (List<Map<String, Object>>) payload.get("candidates");
        assertThat(sentCandidates).hasSize(2);
        assertThat(sentCandidates.get(0).get("driverId")).isEqualTo(driverA.toString()); // gần nhất xếp đầu

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance 2: "không có driver phát đúng event" — stub rỗng → job NO_DRIVER_FOUND +
     *  NoDriverFound {tripId, attempt, reason:"NO_CANDIDATES"} (§3.3), không có DriverProposed. */
    @org.junit.jupiter.api.Test
    void noCandidatesEmitsNoDriverFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        // Stub mặc định: {"candidates":[]}

        publishTripRequested(eventId, correlationId, tripId, 1, List.of());
        waitForInboxRow(eventId);

        assertThat(jobState(tripId, 1)).isEqualTo("NO_DRIVER_FOUND");
        assertThat(candidatesOfJob(tripId, 1)).isEmpty();

        UUID outboxEventId = waitForOutboxSent(tripId, eventId);
        Message msg = receiveFromTripInbox(outboxEventId.toString());
        assertThat(msg.getMessageProperties().getType()).isEqualTo("NoDriverFound");
        assertPersistent(msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> env = JSON.readValue(msg.getBody(), Map.class);
        assertThat(env.get("causationId")).isEqualTo(eventId.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload.get("tripId")).isEqualTo(tripId.toString());
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);
        assertThat(payload.get("reason")).isEqualTo(MatchingJobHandler.REASON_NO_CANDIDATES);

        // Không có row DriverProposed nào cho trip này.
        Integer proposed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'DriverProposed'",
                Integer.class, tripId);
        assertThat(proposed).isZero();

        assertNoDlqMessage(eventId.toString());
    }

    /** excludedDriverIds được lọc phía matching (API không nhận tham số exclude — contract §4.2):
     *  A bị exclude → proposal chỉ còn B. */
    @org.junit.jupiter.api.Test
    void excludedDriversAreFilteredFromProposal() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID driverA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID driverB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        DRIVER_STUB.responseJson = candidatesJson(
                new StubCandidate(driverA, userA, 412L),
                new StubCandidate(driverB, userB, 987L));

        publishTripRequested(eventId, correlationId, tripId, 1, List.of(driverA));
        waitForInboxRow(eventId);

        assertThat(jobState(tripId, 1)).isEqualTo("PROPOSED");
        List<Map<String, Object>> candidates = candidatesOfJob(tripId, 1);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("driverId")).isEqualTo(driverB.toString());

        UUID outboxEventId = waitForOutboxSent(tripId, eventId);
        Message msg = receiveFromTripInbox(outboxEventId.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> env = JSON.readValue(msg.getBody(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentCandidates = (List<Map<String, Object>>) payload.get("candidates");
        assertThat(sentCandidates).hasSize(1);
        assertThat(sentCandidates.get(0).get("driverId")).isEqualTo(driverB.toString());

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance 3: "replay không tạo job trùng" — redelivery cùng eventId → dedup §4.1:
     *  đúng 1 row matching_jobs, 1 row outbox (causation match), 1 row inbox_events. */
    @org.junit.jupiter.api.Test
    void replayDoesNotCreateDuplicateJobOrEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID driverA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        DRIVER_STUB.responseJson = candidatesJson(new StubCandidate(driverA, userA, 412L));

        publishTripRequested(eventId, correlationId, tripId, 1, List.of());
        waitForInboxRow(eventId);

        // Mô phỏng at-least-once redelivery: publish lại envelope cùng eventId.
        publishTripRequested(eventId, correlationId, tripId, 1, List.of());
        LogCapture capture = attachLogCapture();
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (capture.count(correlationId.toString(), "inbox_duplicate", "success") >= 1) {
                    break;
                }
                Thread.sleep(100);
            }
        } finally {
            detachLogCapture(capture);
        }

        assertThat(inboxCount(eventId)).isEqualTo(1);   // dedup record duy nhất
        assertThat(jobCount(tripId)).isEqualTo(1);      // không có job trùng (trip, attempt)
        assertThat(outboxCount(tripId, eventId)).isEqualTo(1); // một event DriverProposed duy nhất

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance 4: "API Driver timeout đi nhánh retry" — call đầu timeout (> read-timeout client),
     *  listener retry bounded (events.md §4.3), attempt kế tiếp thành công; không vào DLQ. */
    @org.junit.jupiter.api.Test
    void driverApiTimeoutRetriesThenSucceeds() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID driverA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        DRIVER_STUB.responseJson = candidatesJson(new StubCandidate(driverA, userA, 412L));
        // > read-timeout client (800 ms) → timeout thật; < 800 + backoff 300 ms để handler call 1
        // kịp kết thúc trước khi call 2 đến (executor tuần tự của JDK HttpServer).
        DRIVER_STUB.delayMs = 900;
        DRIVER_STUB.timeoutCallsRemaining.set(1);   // chỉ call đầu tiên bị timeout

        LogCapture capture = attachLogCapture();
        try {
            publishTripRequested(eventId, correlationId, tripId, 1, List.of());
            waitForInboxRow(eventId);

            assertThat(jobCount(tripId)).isEqualTo(1);
            assertThat(inboxCount(eventId)).isEqualTo(1);
            // Attempt 1 timeout → đúng 1 log inbox_retry; attempt 2 commit.
            assertThat(capture.count(correlationId.toString(), "inbox_retry", "failure")).isEqualTo(1);
            assertThat(capture.count(correlationId.toString(), "inbox_processed", "success")).isEqualTo(1);

            assertNoDlqMessage(eventId.toString()); // retry thành công — không vào DLQ
        } finally {
            detachLogCapture(capture);
        }
    }

    /** Acceptance 4 (tiếp): timeout kéo dài hết lượt retry → reject(requeue=false) → DLQ chỉ x-death,
     *  KHÔNG x-dlq-reason; rollback mọi attempt → không có job/inbox row (§4.3/§6b). */
    @org.junit.jupiter.api.Test
    void driverApiTimeoutExhaustsGoesToDlq() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        DRIVER_STUB.delayMs = 2000;                 // mọi call đều timeout (>800 ms)
        DRIVER_STUB.timeoutCallsRemaining.set(100);

        LogCapture capture = attachLogCapture();
        try {
            publishTripRequested(eventId, correlationId, tripId, 1, List.of());
            Message dlq = receiveFromDlq(eventId.toString());

            // Broker dead-letter: có x-death, KHÔNG có x-dlq-reason (khác đường republish chủ động §6a).
            Object xDeath = dlq.getMessageProperties().getHeader("x-death");
            assertThat(xDeath).isNotNull();
            assertThat(dlq.getMessageProperties().getHeaders()).doesNotContainKey(DlqRepublisher.DLQ_REASON_HEADER);
            assertPersistent(dlq);

            // Rollback 4 lần → không có job, không có inbox row (không đánh dấu thành công).
            assertThat(jobCount(tripId)).isZero();
            assertThat(inboxCount(eventId)).isZero();

            // Attempt đầu + 3 retry = 4 execution: đúng 3 log inbox_retry failure + 1 give-up.
            assertThat(capture.count(correlationId.toString(), "inbox_retry", "failure")).isEqualTo(3);
            assertThat(capture.count(correlationId.toString(), "inbox_dlq", "retry_exhausted")).isEqualTo(1);
        } finally {
            detachLogCapture(capture);
        }
    }

    /** Stale-event guard (events.md §4.4): attempt cũ hơn job đã ghi → log + bỏ qua, ACK an toàn —
     *  dedup record vẫn commit nhưng KHÔNG ghi job/outbox mới, không vào DLQ. */
    @org.junit.jupiter.api.Test
    void staleAttemptIsSkippedWithAck() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID driverA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        DRIVER_STUB.responseJson = candidatesJson(new StubCandidate(driverA, userA, 412L));

        // Trước: attempt=2 (mới hơn) → job PROPOSED + outbox SENT.
        UUID eventIdNew = UUID.randomUUID();
        UUID correlationIdNew = UUID.randomUUID();
        publishTripRequested(eventIdNew, correlationIdNew, tripId, 2, List.of());
        waitForInboxRow(eventIdNew);
        UUID outboxEventId = waitForOutboxSent(tripId, eventIdNew);
        receiveFromTripInbox(outboxEventId.toString()); // rút message để không lẫn vào assertion sau

        LogCapture capture = attachLogCapture();
        try {
            // Sau: attempt=1 (cũ hơn) với eventId KHÁC → stale guard skip + ACK.
            UUID eventIdOld = UUID.randomUUID();
            UUID correlationIdOld = UUID.randomUUID();
            publishTripRequested(eventIdOld, correlationIdOld, tripId, 1, List.of());
            waitForInboxRow(eventIdOld);

            // Dedup record commit (event hợp lệ) nhưng không ghi job/outbox mới.
            assertThat(inboxCount(eventIdOld)).isEqualTo(1);
            assertThat(jobCount(tripId)).isEqualTo(1);              // chỉ attempt=2
            assertThat(outboxCount(tripId, eventIdOld)).isZero();

            assertThat(capture.count(correlationIdOld.toString(), "stale_event_skipped", "SUCCESS")).isEqualTo(1);
            assertNoDlqMessage(eventIdOld.toString());
        } finally {
            detachLogCapture(capture);
        }
    }

    // ---------- sửa lỗi review T15 ----------

    /** M1: danh sách loại trừ được gửi cho driver-service để loại TRƯỚC limit (không chỉ lọc sau top-N). */
    @org.junit.jupiter.api.Test
    void excludedDriverIdsAreSentToDriverApi() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID excludedA = UUID.randomUUID();
        UUID excludedB = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        DRIVER_STUB.responseJson = candidatesJson(new StubCandidate(UUID.randomUUID(), UUID.randomUUID(), 900L));

        publishTripRequested(eventId, UUID.randomUUID(), tripId, 2, List.of(excludedA, excludedB));
        waitForInboxRow(eventId);

        assertThat(DRIVER_STUB.lastQuery).contains("excludeDriverIds=" + excludedA + "," + excludedB);
        assertThat(DRIVER_STUB.lastQuery).contains("limit=5");
    }

    /** M3: TripRequested KHÁC eventId nhưng CÙNG attempt đã có job → bỏ qua + ACK (trước đây đụng unique
     *  (trip_id, attempt) → retry → DLQ). */
    @org.junit.jupiter.api.Test
    void sameAttemptWithDifferentEventIdIsSkippedNotDeadLettered() throws Exception {
        UUID tripId = UUID.randomUUID();
        DRIVER_STUB.responseJson = candidatesJson(new StubCandidate(UUID.randomUUID(), UUID.randomUUID(), 412L));
        UUID first = UUID.randomUUID();
        publishTripRequested(first, UUID.randomUUID(), tripId, 1, List.of());
        waitForInboxRow(first);

        LogCapture capture = attachLogCapture();
        try {
            UUID second = UUID.randomUUID();
            UUID correlation = UUID.randomUUID();
            publishTripRequested(second, correlation, tripId, 1, List.of());
            waitForInboxRow(second);

            assertThat(jobCount(tripId)).isEqualTo(1);
            assertThat(outboxCount(tripId, second)).isZero();
            assertThat(capture.count(correlation.toString(), "duplicate_attempt_skipped", "SUCCESS")).isEqualTo(1);
            assertThat(capture.count(correlation.toString(), "inbox_retry", "failure")).isZero();
            assertNoDlqMessage(second.toString());
        } finally {
            detachLogCapture(capture);
        }
    }

    /** M4: log của listener dùng correlationId trong envelope (events.md §5), kể cả khi AMQP property khác/thiếu. */
    @org.junit.jupiter.api.Test
    void listenerLogsWithEnvelopeCorrelationId() throws Exception {
        UUID tripId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID envelopeCorrelation = UUID.randomUUID();
        DRIVER_STUB.responseJson = "{\"candidates\":[]}";
        LogCapture capture = attachLogCapture();
        try {
            byte[] body = envelopeBytes(eventId, envelopeCorrelation, tripId, 1, List.of());
            MessageProperties props = baseProps(eventId, envelopeCorrelation, "TripRequested");
            props.setCorrelationId(null); // AMQP property thiếu — trước đây listener sinh UUID ngẫu nhiên
            rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_REQUESTED_ROUTING_KEY,
                    new Message(body, props));
            waitForInboxRow(eventId);
            long deadline = System.currentTimeMillis() + 5_000;
            while (capture.count(envelopeCorrelation.toString(), "inbox_processed", "success") == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(capture.count(envelopeCorrelation.toString(), "inbox_processed", "success")).isEqualTo(1);
        } finally {
            detachLogCapture(capture);
        }
    }
}
