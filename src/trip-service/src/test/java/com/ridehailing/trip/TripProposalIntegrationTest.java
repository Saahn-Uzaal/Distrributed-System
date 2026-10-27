package com.ridehailing.trip;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.ridehailing.trip.config.RabbitTopologyConfig;
import com.ridehailing.trip.inbox.TripInboxListener;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.Role;
import com.ridehailing.trip.service.TripInboxHandler;
import com.ridehailing.trip.service.TripService;
import com.ridehailing.trip.web.dto.QuoteRequest;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T16 — trip nhận proposal và reserve (spec §20.4) trên PostgreSQL thật + RabbitMQ thật
 * (Testcontainers): consume {@code DriverProposed}/{@code NoDriverFound} từ queue {@code trip.inbox}
 * → thử reserve candidate theo thứ tự qua internal API driver-service (stub HTTP loopback) →
 * lưu reservationId rồi cập nhật DRIVER_OFFERED; hết candidate/NoDriverFound đúng attempt →
 * retry attempt+1 hoặc NO_DRIVER.
 *
 * <p>Acceptance spec §20.4: "Proposal stale bị bỏ qua có log; reserve conflict thử candidate tiếp
 *  theo; timeout đọc lại cùng reservation; không giữ DB lock khi gọi HTTP." events.md §3.2/§3.3/§4,
 * state-machines.md §1/§2 và api-contracts.md §4.1/§4.2 là contract.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
// Đóng context (listener + outbox worker) khi xong class, không để consumer chạy tiếp
// khi container đã dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripProposalIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("trip_test")
            .withPassword("trip_test_pw");

    /** Cổng host CỐ ĐỊNH cho AMQP (pattern T13/T14/T15): Testcontainers mặc định gán cổng host
     *  ngẫu nhiên; cố định giúp app cấu hình một lần và kết nối lại đúng cổng nếu broker restart. */
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

    /** Stub internal reservation API driver-service (contract §4.2) — JDK HttpServer trên loopback,
     *  KHÔNG thêm dependency; RestClient gọi HTTP thật qua loopback. Khởi động trước Spring context
     *  (static init) để DynamicPropertySource đọc được port. */
    static final DriverReservationStub DRIVER_STUB = new DriverReservationStub();

    private static int freeLocalPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** JWT public key test — SecurityConfig/JwtVerifier vẫn khởi tạo trong context MOCK; bean cần
     *  file PEM hợp lệ (pattern TripIntegrationTest). */
    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-trip-t16");
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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Bean JwtVerifier vẫn khởi tạo dù test không verify token thật — dùng public key test.
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(AMQP_HOST_PORT));
        registry.add("spring.rabbitmq.username", () -> "ridehailing_dev");
        registry.add("spring.rabbitmq.password", () -> "rabbit_dev_2026");
        // Backoff ngắn cho test (mặc định production 1s/3s/10s).
        registry.add("app.inbox.retry-backoff-ms", () -> "300,700,1500");
        // T16: stub driver-service + token internal (N09: không có default trong yml).
        registry.add("app.trip.driver-service-url", () -> "http://localhost:" + DRIVER_STUB.port());
        registry.add("app.trip.internal-api-token", () -> "test_internal_token_2026");
        // Read timeout 800 ms < delay stub (900 ms) → nhánh timeout thật của RestClient.
        registry.add("app.trip.http-timeout-ms", () -> "800");
        // Outbox worker chạy nhanh trong test (mặc định production 1000 ms).
        registry.add("app.outbox.publish-interval-ms", () -> "200");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private TripService tripService;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------- stub driver-service (internal reservation API, contract §4.2) ----------

    /** Stub PUT/GET {@code /internal/reservations/{id}}: behavior per reservationId ("201" mặc định
     *  hoặc "409:<CODE>"); delayMs/timeoutCallsRemaining cho test timeout; ghi lại body PUT + thứ tự
     *  call để assert. GET trả RESERVED theo body PUT đã ghi (mô phỏng driver-side replay §4.1) —
     *  404 nếu chưa từng thấy reservationId. */
    static final class DriverReservationStub {
        private final HttpServer server;
        volatile long delayMs = 0;
        final AtomicInteger timeoutCallsRemaining = new AtomicInteger(0);
        /** reservationId -> "201" (mặc định) hoặc "409:<CODE>". */
        final Map<String, String> behaviorByReservationId = new ConcurrentHashMap<>();
        /** Body PUT đã ghi theo reservationId — GET reconcile đọc từ đây. */
        final Map<String, Map<String, Object>> reserveBodyByRid = new ConcurrentHashMap<>();
        final java.util.Set<String> released = ConcurrentHashMap.newKeySet();
        /** Body PUT theo thứ tự call (assert fareVnd/attempt/commandVersion...). */
        final List<Map<String, Object>> reserveBodies = Collections.synchronizedList(new ArrayList<>());
        /** HTTP calls theo thứ tự: "PUT:<rid>" / "GET:<rid>". */
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        DriverReservationStub() {
            try {
                // JDK 21: executor mặc định xử lý tuần tự — test timeout chọn delayMs (900 ms) >
                // read-timeout client (800 ms); GET reconcile xếp hàng sau PUT đang sleep và nhận
                // response ngay khi PUT kết thúc.
                this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                this.server.createContext("/internal/reservations/", exchange -> handle(exchange));
                this.server.start();
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        int port() {
            return server.getAddress().getPort();
        }

        void reset() {
            delayMs = 0;
            timeoutCallsRemaining.set(0);
            behaviorByReservationId.clear();
            reserveBodyByRid.clear();
            released.clear();
            reserveBodies.clear();
            calls.clear();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String rid = path.substring(path.lastIndexOf('/') + 1);
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/release")) {
                String reservationId = path.substring("/internal/reservations/".length(), path.length() - "/release".length());
                released.add(reservationId);
                calls.add("POST:" + reservationId + "/release");
                send(exchange, 200, ("{\"reservationId\":\"" + reservationId
                        + "\",\"state\":\"RELEASED\",\"version\":3}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (delayMs > 0 && timeoutCallsRemaining.decrementAndGet() >= 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod().toUpperCase();
            if ("PUT".equals(method)) {
                calls.add("PUT:" + rid);
                if (released.contains(rid)) {
                    send(exchange, 409, "{\"code\":\"TOMBSTONED_RESERVATION\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = JSON.readValue(reqBody, Map.class);
                    reserveBodies.add(body);
                    reserveBodyByRid.put(rid, body);
                } catch (Exception ignored) {
                    // body không parse được — test luôn gửi JSON hợp lệ.
                }
                String behavior = behaviorByReservationId.getOrDefault(rid, "201");
                if (behavior.startsWith("409:")) {
                    byte[] resp = ("{\"code\":\"" + behavior.substring(4)
                            + "\",\"message\":\"reserve conflict\",\"correlationId\":null}")
                            .getBytes(StandardCharsets.UTF_8);
                    send(exchange, 409, resp);
                } else {
                    byte[] resp = ("{\"reservationId\":\"" + rid + "\",\"state\":\"RESERVED\",\"version\":1}")
                            .getBytes(StandardCharsets.UTF_8);
                    send(exchange, 201, resp);
                }
            } else if ("GET".equals(method)) {
                calls.add("GET:" + rid);
                if (released.contains(rid)) {
                    send(exchange, 200, ("{\"reservationId\":\"" + rid + "\",\"state\":\"RELEASED\",\"version\":3}")
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                Map<String, Object> stored = reserveBodyByRid.get(rid);
                if (stored == null) {
                    send(exchange, 404, "{\"code\":\"RESERVATION_NOT_FOUND\",\"message\":\"not found\"}"
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] resp = ("{\"reservationId\":\"" + rid + "\",\"state\":\"RESERVED\","
                            + "\"tripId\":\"" + stored.get("tripId") + "\","
                            + "\"driverId\":\"" + stored.get("driverId") + "\","
                            + "\"attempt\":" + stored.get("attempt") + ",\"version\":1}")
                            .getBytes(StandardCharsets.UTF_8);
                    send(exchange, 200, resp);
                }
            } else {
                exchange.close();
            }
        }

        private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ---------- setup/cleanup ----------

    @BeforeEach
    void resetState() {
        DRIVER_STUB.reset();
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM sagas");
        jdbc.update("DELETE FROM trip_events");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM trips");
        // spring-rabbit 4.x/amqp-client 5: RabbitTemplate không còn purgeQueue, Channel dùng queuePurge.
        rabbitTemplate.execute(channel -> channel.queuePurge(RabbitTopologyConfig.TRIP_INBOX_QUEUE));
    }

    // ---------- trip creation (direct bean call — pattern TripIntegrationTest) ----------

    /** Tạo trip SEARCHING qua {@code TripService.create} trực tiếp (không cần HTTP/JWT): trả tripId. */
    private UUID createTrip(UUID passengerUserId, UUID correlationId) {
        var outcome = tripService.create(
                new AuthenticatedUser(passengerUserId, "passenger-t16@example.com", Role.PASSENGER),
                UUID.randomUUID().toString(),
                new QuoteRequest(new QuoteRequest.Point(10.7769, 106.7009),
                        new QuoteRequest.Point(10.8, 106.72)),
                correlationId);
        assertThat(outcome.httpStatus()).isEqualTo(202);
        return UUID.fromString((String) outcome.body().get("tripId"));
    }

    // ---------- DB helpers ----------

    /** Row trips đủ để assert transition T16 (state/attempt/version/driver fields/fare). */
    private record TripDbState(String state, int matchingAttempt, int version, UUID driverId,
                              UUID driverUserId, UUID reservationId, long distanceM, long fareVnd,
                              double pickupLat, double pickupLng) {
    }

    private TripDbState tripState(UUID tripId) {
        return jdbc.queryForObject(
                "SELECT state, matching_attempt, version, driver_id, driver_user_id, reservation_id,"
                        + " distance_m, fare_vnd, pickup_lat, pickup_lng FROM trips WHERE id = ?",
                (rs, i) -> new TripDbState(
                        rs.getString("state"), rs.getInt("matching_attempt"), rs.getInt("version"),
                        (UUID) rs.getObject("driver_id"), (UUID) rs.getObject("driver_user_id"),
                        (UUID) rs.getObject("reservation_id"), rs.getLong("distance_m"),
                        rs.getLong("fare_vnd"), rs.getDouble("pickup_lat"), rs.getDouble("pickup_lng")),
                tripId);
    }

    private String sagaPhase(UUID tripId) {
        return jdbc.queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId);
    }

    private Instant sagaNextRetryAt(UUID tripId) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT next_retry_at FROM sagas WHERE trip_id = ?", java.sql.Timestamp.class, tripId);
        return ts == null ? null : ts.toInstant();
    }

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private int tripEventCount(String type, UUID tripId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_events WHERE trip_id = ? AND type = ?", Integer.class, tripId, type);
        return n == null ? 0 : n;
    }

    // ---------- publish helpers (envelope theo events.md §2/§3.2/§3.3) ----------

    record Candidate(UUID driverId, UUID driverUserId, long distanceM) {
    }

    private MessageProperties baseProps(UUID eventId, UUID correlationId, String eventType) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());          // messageId = eventId (§1: consumer dedup ở tầng AMQP)
        props.setCorrelationId(correlationId.toString());
        props.setType(eventType);
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return props;
    }

    private byte[] driverProposedBytes(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                       List<Candidate> candidates) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", "DriverProposed");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "matching-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        List<Map<String, Object>> candList = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("driverId", c.driverId().toString());
            m.put("driverUserId", c.driverUserId().toString());
            m.put("distanceM", c.distanceM());
            candList.add(m);
        }
        payload.put("candidates", candList);
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    private byte[] noDriverFoundBytes(UUID eventId, UUID correlationId, UUID tripId, int attempt) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", "NoDriverFound");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "matching-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("reason", "NO_CANDIDATES"); // §3.3: reason enum duy nhất
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish qua exchange ride.events — routing key matching.proposed/matching.unavailable
     *  (events.md §3.2/§3.3) → routed vào trip.inbox (topology service declare ở T16). */
    private void publish(UUID eventId, UUID correlationId, String eventType, byte[] body) {
        String rk = "DriverProposed".equals(eventType) ? "matching.proposed" : "matching.unavailable";
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, rk,
                new Message(body, baseProps(eventId, correlationId, eventType)));
    }

    private void publishDriverProposed(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                       List<Candidate> candidates) throws Exception {
        publish(eventId, correlationId, "DriverProposed", driverProposedBytes(eventId, correlationId, tripId, attempt, candidates));
    }

    private void publishNoDriverFound(UUID eventId, UUID correlationId, UUID tripId, int attempt) throws Exception {
        publish(eventId, correlationId, "NoDriverFound", noDriverFoundBytes(eventId, correlationId, tripId, attempt));
    }

    // ---------- wait/receive helpers ----------

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

    /** Chờ trips.state == expected; trả row hiện tại. */
    private TripDbState waitForTripState(UUID tripId, String state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            TripDbState s = tripState(tripId);
            if (state.equals(s.state())) {
                return s;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("trip " + tripId + " not in state " + state + " within 30 s");
    }

    /** Chờ trips.matching_attempt == expected (state vẫn SEARCHING). */
    private TripDbState waitForMatchingAttempt(UUID tripId, int attempt) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            TripDbState s = tripState(tripId);
            if ("SEARCHING".equals(s.state()) && s.matchingAttempt() == attempt) {
                return s;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("trip " + tripId + " not at matching_attempt " + attempt + " within 30 s");
    }

    /** Nhận message từ trip.dlq khớp messageId; timeout 30 s (đủ cho backoff test 300/700/1500 ms). */
    private Message receiveFromDlq(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.TRIP_DLQ_QUEUE, 1_000);
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
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.TRIP_DLQ_QUEUE, 1_000);
            if (msg != null && messageId.equals(msg.getMessageProperties().getMessageId())) {
                throw new AssertionError("unexpected DLQ message with messageId " + messageId);
            }
        }
    }

    // ---------- log assertions (structured JSON, spec §11) ----------

    /** Bắt log của CẢ listener (inbox_processed/inbox_duplicate/...) và handler
     *  (stale_event_skipped/trip_driver_offered/...). */
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
        ((Logger) LoggerFactory.getLogger(TripInboxListener.class)).addAppender(listener);
        ListAppender<ILoggingEvent> handler = new ListAppender<>();
        handler.start();
        ((Logger) LoggerFactory.getLogger(TripInboxHandler.class)).addAppender(handler);
        return new LogCapture(listener, handler);
    }

    private void detachLogCapture(LogCapture capture) {
        ((Logger) LoggerFactory.getLogger(TripInboxListener.class)).detachAppender(capture.listenerAppender());
        ((Logger) LoggerFactory.getLogger(TripInboxHandler.class)).detachAppender(capture.handlerAppender());
    }

    // ---------- deterministic ids (cùng công thức TripInboxHandler — §4.1 idempotency) ----------

    private static UUID reservationId(UUID tripId, int attempt, int index) {
        return UUID.nameUUIDFromBytes(("RESERVE|" + tripId + "|" + attempt + "|" + index)
                .getBytes(StandardCharsets.UTF_8));
    }

    // ---------- tests ----------

    /** Acceptance: "lưu reservationId rồi gọi reserve; cập nhật DRIVER_OFFERED" — candidate đầu
     *  thắng (201) → trips DRIVER_OFFERED với driver fields + reservation_id, saga WAITING_DRIVER,
     *  trip_event DRIVER_OFFERED; body PUT đúng contract §4.2. */
    @org.junit.jupiter.api.Test
    void driverProposedReservesFirstCandidateAndOffers() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID driverA = UUID.randomUUID(), userA = UUID.randomUUID();
        UUID driverB = UUID.randomUUID(), userB = UUID.randomUUID();

        // Stub mặc định: mọi PUT → 201.
        UUID eventId = UUID.randomUUID();
        publishDriverProposed(eventId, correlationId, tripId, 1,
                List.of(new Candidate(driverA, userA, 412L), new Candidate(driverB, userB, 987L)));

        TripDbState s = waitForTripState(tripId, "DRIVER_OFFERED");
        // Winner = candidate đầu (A) — giữ được chỗ, dừng ngay (§3.2).
        assertThat(s.driverId()).isEqualTo(driverA);
        assertThat(s.driverUserId()).isEqualTo(userA);
        UUID expectedRid = reservationId(tripId, 1, 0);
        assertThat(s.reservationId()).isEqualTo(expectedRid);
        assertThat(s.version()).isEqualTo(2);

        // Saga WAITING_DRIVER (state-machines.md §1).
        assertThat(sagaPhase(tripId)).isEqualTo("WAITING_DRIVER");

        // trip_event DRIVER_OFFERED với version mới của trips.
        Integer evVersion = jdbc.queryForObject(
                "SELECT version FROM trip_events WHERE trip_id = ? AND type = 'DRIVER_OFFERED'",
                Integer.class, tripId);
        assertThat(evVersion).isEqualTo(2);

        // Body PUT đúng contract §4.2: commandVersion 1, attempt, fare/distance/pickup từ trips row.
        Map<String, Object> body = DRIVER_STUB.reserveBodies.get(0);
        assertThat(body.get("commandVersion")).isEqualTo(1);
        assertThat(((Number) body.get("attempt")).intValue()).isEqualTo(1);
        assertThat(body.get("tripId")).isEqualTo(tripId.toString());
        assertThat(body.get("driverId")).isEqualTo(driverA.toString());
        assertThat(((Number) body.get("fareVnd")).longValue()).isEqualTo(s.fareVnd());
        assertThat(((Number) body.get("distanceM")).longValue()).isEqualTo(s.distanceM());
        @SuppressWarnings("unchecked")
        Map<String, Object> pickup = (Map<String, Object>) body.get("pickup");
        assertThat(pickup.get("latitude")).isEqualTo(s.pickupLat());
        assertThat(pickup.get("longitude")).isEqualTo(s.pickupLng());
        // expiresAt trong (now, now+120s] (§4.1: OFFER_TTL_MAX).
        Instant expiresAt = Instant.parse((String) body.get("expiresAt"));
        assertThat(expiresAt.isAfter(Instant.now().minusSeconds(5))).isTrue();
        assertThat(expiresAt.isBefore(Instant.now().plusSeconds(125))).isTrue();

        // Chỉ MỘT PUT (candidate A thắng — không thử B).
        assertThat(DRIVER_STUB.calls).containsExactly("PUT:" + expectedRid);

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "Proposal stale bị bỏ qua có log" — attempt cũ hơn matching_attempt hiện tại →
     *  log stale_event_skipped + ACK; dedup row commit nhưng KHÔNG thay đổi nghiệp vụ, không DLQ. */
    @org.junit.jupiter.api.Test
    void staleProposalIsSkippedWithLog() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);

        // Trước: NoDriverFound attempt 1 → retry attempt 2 (SEARCHING).
        publishNoDriverFound(UUID.randomUUID(), correlationId, tripId, 1);
        waitForMatchingAttempt(tripId, 2);

        LogCapture capture = attachLogCapture();
        try {
            // Sau: DriverProposed attempt CŨ (1) với eventId/correlation khác → stale guard skip.
            UUID dpEventId = UUID.randomUUID();
            UUID correlationOld = UUID.randomUUID();
            publishDriverProposed(dpEventId, correlationOld, tripId, 1,
                    List.of(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 412L)));
            waitForInboxRow(dpEventId);

            // Dedup record commit (event hợp lệ) nhưng KHÔNG có thay đổi nghiệp vụ.
            assertThat(inboxCount(dpEventId)).isEqualTo(1);
            TripDbState s = tripState(tripId);
            assertThat(s.state()).isEqualTo("SEARCHING");
            assertThat(s.matchingAttempt()).isEqualTo(2);
            assertThat(s.driverId()).isNull();
            assertThat(tripEventCount("DRIVER_OFFERED", tripId)).isZero();
            // Không gọi reserve nào cho event stale.
            assertThat(DRIVER_STUB.calls).isEmpty();

            assertThat(capture.count(correlationOld.toString(), "stale_event_skipped", "SUCCESS")).isEqualTo(1);
            assertNoDlqMessage(dpEventId.toString());
        } finally {
            detachLogCapture(capture);
        }
    }

    /** Acceptance: "reserve conflict thử candidate tiếp theo" — A 409 DRIVER_NOT_AVAILABLE →
     *  thử B; B thắng → trips.driver_id = B, thứ tự call A→B. */
    @org.junit.jupiter.api.Test
    void reserveConflictTriesNextCandidate() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID driverA = UUID.randomUUID(), userA = UUID.randomUUID();
        UUID driverB = UUID.randomUUID(), userB = UUID.randomUUID();

        // Candidate A conflict → thử B (§3.2).
        DRIVER_STUB.behaviorByReservationId.put(reservationId(tripId, 1, 0).toString(), "409:DRIVER_NOT_AVAILABLE");

        publishDriverProposed(UUID.randomUUID(), correlationId, tripId, 1,
                List.of(new Candidate(driverA, userA, 412L), new Candidate(driverB, userB, 987L)));

        TripDbState s = waitForTripState(tripId, "DRIVER_OFFERED");
        assertThat(s.driverId()).isEqualTo(driverB);
        assertThat(s.driverUserId()).isEqualTo(userB);
        assertThat(s.reservationId()).isEqualTo(reservationId(tripId, 1, 1));

        // Thứ tự call: A trước rồi B.
        assertThat(DRIVER_STUB.calls).containsExactly(
                "PUT:" + reservationId(tripId, 1, 0), "PUT:" + reservationId(tripId, 1, 1));
    }

    /** Acceptance: "timeout đọc lại cùng reservation" — PUT đầu timeout (> read-timeout client) →
     *  GET CÙNG reservationId xác nhận RESERVED → success với candidate đó; KHÔNG có PUT thứ hai. */
    @org.junit.jupiter.api.Test
    void reserveTimeoutReconcilesViaGet() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID driverA = UUID.randomUUID(), userA = UUID.randomUUID();

        // PUT đầu timeout (> read-timeout client 800 ms) → handler GET reconcile CÙNG reservationId.
        DRIVER_STUB.delayMs = 900;
        DRIVER_STUB.timeoutCallsRemaining.set(1);

        publishDriverProposed(UUID.randomUUID(), correlationId, tripId, 1,
                List.of(new Candidate(driverA, userA, 412L)));

        TripDbState s = waitForTripState(tripId, "DRIVER_OFFERED");
        UUID rid = reservationId(tripId, 1, 0);
        assertThat(s.driverId()).isEqualTo(driverA);
        assertThat(s.reservationId()).isEqualTo(rid);

        // PUT timeout + GET reconcile — KHÔNG có PUT thứ hai (GET đã xác nhận RESERVED).
        assertThat(DRIVER_STUB.calls).containsExactly("PUT:" + rid, "GET:" + rid);
    }

    /** events.md §3.2/§3.3: NoDriverFound đúng attempt chưa exhausted → SEARCHING→SEARCHING
     *  attempt+1, saga next_retry_at = now + MATCHING_RETRY_DELAY_MS, outbox PENDING TripRequested
     *  attempt mới với causationId = eventId event này (§5). */
    @org.junit.jupiter.api.Test
    void noDriverFoundRetriesNextAttempt() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);

        Instant t0 = Instant.now();
        UUID ndfEventId = UUID.randomUUID();
        publishNoDriverFound(ndfEventId, correlationId, tripId, 1);
        TripDbState s = waitForMatchingAttempt(tripId, 2);

        // SEARCHING → SEARCHING attempt+1 (events.md §3.2).
        assertThat(s.state()).isEqualTo("SEARCHING");
        assertThat(s.version()).isEqualTo(2);

        // Saga WAITING_MATCH + next_retry_at ≈ now + MATCHING_RETRY_DELAY_MS (5000 ms default).
        assertThat(sagaPhase(tripId)).isEqualTo("WAITING_MATCH");
        Instant nextRetryAt = sagaNextRetryAt(tripId);
        assertThat(nextRetryAt).isNotNull();
        assertThat(nextRetryAt.isAfter(t0.plusSeconds(4))).isTrue();
        assertThat(nextRetryAt.isBefore(Instant.now().plusSeconds(7))).isTrue();

        // Outbox PENDING TripRequested attempt mới, causationId = eventId NoDriverFound (§5).
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, ndfEventId);
        assertThat(n).isEqualTo(1);
        String payloadJson = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                String.class, tripId, ndfEventId);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = JSON.readValue(payloadJson.getBytes(StandardCharsets.UTF_8), Map.class);
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(2);
        assertThat(payload.get("tripId")).isEqualTo(tripId.toString());
        Integer aggVersion = jdbc.queryForObject(
                "SELECT aggregate_version FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, ndfEventId);
        assertThat(aggVersion).isEqualTo(s.version()); // trips.version mới

        // T16 fix (T1): row outbox chỉ được publish sau MATCHING_RETRY_DELAY_MS — next_attempt_at ≈ +5 s
        // và row vẫn PENDING ngay sau khi xử lý (trước đây publish tức thì).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, next_attempt_at - clock_timestamp() > interval '3 seconds' AS delayed"
                        + " FROM outbox WHERE aggregate_id = ? AND causation_id = ?", tripId, ndfEventId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("delayed")).isEqualTo(true);

        // Không có trip_event mới (chỉ TRIP_CREATED từ create).
        Integer evCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_events WHERE trip_id = ?", Integer.class, tripId);
        assertThat(evCount).isEqualTo(1);
    }

    /** events.md §3.2: attempt >= MAX_MATCHING_ATTEMPTS (3) → NO_DRIVER terminal + trip_event
     *  NO_DRIVER + saga ABORTED; KHÔNG phát event mới. */
    @org.junit.jupiter.api.Test
    void noDriverFoundExhaustsToNoDriver() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);

        // Ba vòng NoDF: attempt 1 → 2 → 3; attempt 3 >= MAX_MATCHING_ATTEMPTS (3) → NO_DRIVER.
        publishNoDriverFound(UUID.randomUUID(), correlationId, tripId, 1);
        waitForMatchingAttempt(tripId, 2);
        publishNoDriverFound(UUID.randomUUID(), correlationId, tripId, 2);
        waitForMatchingAttempt(tripId, 3);

        UUID lastEventId = UUID.randomUUID();
        publishNoDriverFound(lastEventId, correlationId, tripId, 3);
        TripDbState s = waitForTripState(tripId, "NO_DRIVER");

        assertThat(s.matchingAttempt()).isEqualTo(3);
        assertThat(s.version()).isEqualTo(4);
        assertThat(sagaPhase(tripId)).isEqualTo("ABORTED");

        Integer noDriverEvVersion = jdbc.queryForObject(
                "SELECT version FROM trip_events WHERE trip_id = ? AND type = 'NO_DRIVER'",
                Integer.class, tripId);
        assertThat(noDriverEvVersion).isEqualTo(4);

        // NO_DRIVER terminal — không phát event mới (không outbox row cho event cuối).
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, lastEventId);
        assertThat(n).isZero();

        // Tổng: 1 TripRequested gốc (create) + 2 retry (attempt 2, 3).
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'TripRequested'",
                Integer.class, tripId);
        assertThat(total).isEqualTo(3);
    }

    /** events.md §3.2: hết candidate đúng attempt (mọi reserve 409) → SEARCHING→SEARCHING
     *  attempt+1 + outbox TripRequested causationId = eventId DriverProposed (§5). */
    @org.junit.jupiter.api.Test
    void exhaustedCandidatesRetriesNextAttempt() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID driverA = UUID.randomUUID(), userA = UUID.randomUUID();
        UUID driverB = UUID.randomUUID(), userB = UUID.randomUUID();

        // Mọi candidate conflict → hết candidate đúng attempt → retry attempt+1 (§3.2).
        DRIVER_STUB.behaviorByReservationId.put(reservationId(tripId, 1, 0).toString(), "409:DRIVER_NOT_AVAILABLE");
        DRIVER_STUB.behaviorByReservationId.put(reservationId(tripId, 1, 1).toString(), "409:DRIVER_NOT_AVAILABLE");

        UUID dpEventId = UUID.randomUUID();
        publishDriverProposed(dpEventId, correlationId, tripId, 1,
                List.of(new Candidate(driverA, userA, 412L), new Candidate(driverB, userB, 987L)));

        TripDbState s = waitForMatchingAttempt(tripId, 2);
        assertThat(s.state()).isEqualTo("SEARCHING");
        assertThat(s.version()).isEqualTo(2);
        assertThat(sagaPhase(tripId)).isEqualTo("WAITING_MATCH");
        assertThat(sagaNextRetryAt(tripId)).isNotNull();

        // Outbox PENDING TripRequested attempt 2, causationId = eventId DriverProposed (§5).
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, dpEventId);
        assertThat(n).isEqualTo(1);
        String payloadJson = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                String.class, tripId, dpEventId);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = JSON.readValue(payloadJson.getBytes(StandardCharsets.UTF_8), Map.class);
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(2);

        // Không có DRIVER_OFFERED (chưa giữ được chỗ).
        assertThat(tripEventCount("DRIVER_OFFERED", tripId)).isZero();
    }

    /** Acceptance: dedup §4.1 — redelivery cùng eventId sau success → log inbox_duplicate,
     *  KHÔNG reserve lại, trips không đổi. */
    @org.junit.jupiter.api.Test
    void duplicateProposalProcessedOnce() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID driverA = UUID.randomUUID(), userA = UUID.randomUUID();

        UUID eventId = UUID.randomUUID();
        publishDriverProposed(eventId, correlationId, tripId, 1,
                List.of(new Candidate(driverA, userA, 412L)));
        TripDbState s = waitForTripState(tripId, "DRIVER_OFFERED");

        LogCapture capture = attachLogCapture();
        try {
            // Mô phỏng at-least-once redelivery: publish lại envelope CÙNG eventId.
            publishDriverProposed(eventId, correlationId, tripId, 1,
                    List.of(new Candidate(driverA, userA, 412L)));

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (capture.count(correlationId.toString(), "inbox_duplicate", "success") >= 1) {
                    break;
                }
                Thread.sleep(100);
            }

            // Dedup record duy nhất; không reserve lần hai; trips không đổi.
            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(DRIVER_STUB.calls).containsExactly("PUT:" + reservationId(tripId, 1, 0));
            TripDbState after = tripState(tripId);
            assertThat(after.state()).isEqualTo(s.state());
            assertThat(after.version()).isEqualTo(s.version());
            assertThat(after.driverId()).isEqualTo(driverA);

            assertNoDlqMessage(eventId.toString());
        } finally {
            detachLogCapture(capture);
        }
    }

    // ---------- sửa lỗi review T16 ----------

    /** T2: proposal tới SAU deadline tổng → NO_DRIVER ngay, không reserve tài xế nào (trước đây vẫn giữ chỗ). */
    @org.junit.jupiter.api.Test
    void proposalAfterDeadlineEndsWithNoDriverWithoutReserving() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        jdbc.update("UPDATE sagas SET deadline_at = clock_timestamp() - interval '1 second' WHERE trip_id = ?", tripId);
        int putsBefore = DRIVER_STUB.reserveBodies.size();

        publishDriverProposed(UUID.randomUUID(), correlationId, tripId, 1,
                List.of(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 300L)));

        TripDbState s = waitForTripState(tripId, "NO_DRIVER");
        assertThat(s.state()).isEqualTo("NO_DRIVER");
        assertThat(DRIVER_STUB.reserveBodies.size()).isEqualTo(putsBefore);
        assertThat(sagaPhase(tripId)).isEqualTo("ABORTED");
    }

    /** T4: candidate nằm trong excludedDriverIds của saga bị bỏ qua phía trip (phòng thủ, kể cả khi matching
     *  không lọc) — reserve candidate kế tiếp. */
    @org.junit.jupiter.api.Test
    void excludedCandidateIsNeverReserved() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = createTrip(passenger, correlationId);
        UUID excludedDriver = UUID.randomUUID();
        jdbc.update("UPDATE sagas SET context = jsonb_set(context, '{excludedDriverIds}', ?::jsonb) WHERE trip_id = ?",
                "[\"" + excludedDriver + "\"]", tripId);
        UUID okDriver = UUID.randomUUID();

        publishDriverProposed(UUID.randomUUID(), correlationId, tripId, 1,
                List.of(new Candidate(excludedDriver, UUID.randomUUID(), 100L),
                        new Candidate(okDriver, UUID.randomUUID(), 900L)));

        TripDbState s = waitForTripState(tripId, "DRIVER_OFFERED");
        assertThat(s.driverId()).isEqualTo(okDriver);
        assertThat(DRIVER_STUB.reserveBodies).noneMatch(b -> excludedDriver.toString().equals(b.get("driverId")));
    }
}
