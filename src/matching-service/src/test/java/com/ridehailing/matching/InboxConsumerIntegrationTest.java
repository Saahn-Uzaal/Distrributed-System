package com.ridehailing.matching;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.ridehailing.matching.config.RabbitTopologyConfig;
import com.ridehailing.matching.inbox.DlqRepublisher;
import com.ridehailing.matchingtest.FaultInjectionConfig;
import com.ridehailing.matching.inbox.MatchingInboxListener;
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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T14 — inbox consumer integration test trên PostgreSQL thật + RabbitMQ thật (Testcontainers).
 * Acceptance spec §20.4: duplicate chỉ thực thi handler một lần; rollback không để inbox đánh dấu
 * thành công; malformed vào DLQ; transient retry có giới hạn. events.md §2/§4/§6 là contract.
 */
// Boot 4 bootstrapper: khi test class có nested @Configuration, main app KHÔNG tự merge vào
// context (MergedContextConfiguration chỉ còn FaultInjectionConfig) → khai báo explicit.
// webEnvironment=MOCK (KHÔNG NONE): security auto-config yêu cầu SERVLET web-app type — với NONE
// không có bean HttpSecurity và SecurityConfig.securityFilterChain fail khi load context.
@SpringBootTest(classes = {MatchingServiceApplication.class, FaultInjectionConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
// Đóng context (và listener container) khi xong class, không để consumer chạy tiếp khi container đã dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class InboxConsumerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("matching_test")
            .withPassword("matching_test_pw");

    /** Cổng host CỐ ĐỊNH cho AMQP (pattern T13): Testcontainers mặc định gán cổng host ngẫu nhiên;
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
        // T15: app.matching.internal-api-token không có default (${INTERNAL_API_TOKEN}) —
        // RequiredDbPasswordValidator chặn startup nếu property chưa resolve được.
        registry.add("app.matching.internal-api-token", () -> "smoke_no_token");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------- fault injection test-only (spec §13: chỉ bật trong test) ----------
    // FaultInjectionConfig nằm ở package com.ridehailing.matchingtest (T15): KHÔNG thuộc cây scan
    // của app để component scan theo tên package không phát hiện @Configuration test-scope từ
    // target/test-classes; khai báo explicit trong classes={...} bên trên.

    // ---------- setup/cleanup ----------

    @BeforeEach
    void resetState() {
        FaultInjectionConfig.FAIL_COUNTS.clear();
        FaultInjectionConfig.ATTEMPTS.clear();
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM matching_probe_events");
    }

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private int probeCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching_probe_events WHERE event_id = ?", Integer.class, eventId);
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

    private byte[] envelopeBytes(UUID eventId, UUID correlationId, String eventType,
                                UUID tripId, int attempt) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", eventType);
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "trip-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        env.put("aggregateVersion", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("pickup", Map.of("latitude", 10.7769, "longitude", 106.7009));
        payload.put("excludedDriverIds", List.of());
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish TripRequested qua exchange ride.events rk trip.requested (đường đi thật của T13). */
    private void publishTripRequested(UUID eventId, UUID correlationId, UUID tripId) throws Exception {
        byte[] body = envelopeBytes(eventId, correlationId, "TripRequested", tripId, 1);
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_REQUESTED_ROUTING_KEY,
                new Message(body, baseProps(eventId, correlationId, "TripRequested")));
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

    // ---------- log assertions (structured JSON, spec §11) ----------

    private static ListAppender<ILoggingEvent> attachListenerAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(MatchingInboxListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachListenerAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(MatchingInboxListener.class)).detachAppender(appender);
    }

    private long countLogs(ListAppender<ILoggingEvent> appender, String correlationId,
                           String eventType, String result) {
        return new ArrayList<>(appender.list).stream().filter(e ->
                correlationId.equals(e.getMDCPropertyMap().get("correlationId"))
                        && eventType.equals(e.getMDCPropertyMap().get("eventType"))
                        && result.equals(e.getMDCPropertyMap().get("result"))).count();
    }

    // ---------- tests ----------

    /** Acceptance 1: duplicate chỉ thực thi handler một lần (dedup §4.1, ACK sau commit §4.2). */
    @org.junit.jupiter.api.Test
    void validEventProcessedOnceAndDuplicateIsIgnored() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        ListAppender<ILoggingEvent> appender = attachListenerAppender();
        try {
            publishTripRequested(eventId, correlationId, tripId);
            waitForInboxRow(eventId);

            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(probeCount(eventId)).isEqualTo(1);
            // ATTEMPTS đếm MỌI invocation handler — bằng chứng trực tiếp "chạy đúng một lần".
            assertThat(Integer.valueOf(FaultInjectionConfig.ATTEMPTS.getOrDefault(eventId, 0))).isEqualTo(1);

            // Mô phỏng at-least-once redelivery: publish lại envelope cùng eventId.
            publishTripRequested(eventId, correlationId, tripId);
            waitForDuplicateLog(appender, correlationId.toString());

            assertThat(inboxCount(eventId)).isEqualTo(1);   // không có row thứ hai
            assertThat(probeCount(eventId)).isEqualTo(1);   // handler không chạy lại
            assertThat(Integer.valueOf(FaultInjectionConfig.ATTEMPTS.getOrDefault(eventId, 0))).isEqualTo(1);
            assertThat(countLogs(appender, correlationId.toString(), "inbox_processed", "success")).isEqualTo(1);
            assertThat(countLogs(appender, correlationId.toString(), "inbox_duplicate", "success")).isEqualTo(1);

            assertNoDlqMessage(eventId.toString()); // event hợp lệ — không vào DLQ
        } finally {
            detachListenerAppender(appender);
        }
    }

    private void waitForDuplicateLog(ListAppender<ILoggingEvent> appender, String correlationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (countLogs(appender, correlationId, "inbox_duplicate", "success") >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no inbox_duplicate log for correlationId " + correlationId + " within 30 s");
    }

    /** Acceptance 2: malformed/unsupported → DLQ kèm x-dlq-reason, KHÔNG có x-death (§2/§6a). */
    @org.junit.jupiter.api.Test
    void malformedAndUnsupportedGoToDlqWithReason() throws Exception {
        // (a) body không phải JSON.
        UUID eventIdA = UUID.randomUUID();
        UUID correlationIdA = UUID.randomUUID();
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_REQUESTED_ROUTING_KEY,
                new Message("this is not json".getBytes(StandardCharsets.UTF_8),
                        baseProps(eventIdA, correlationIdA, "TripRequested")));

        Message dlqA = receiveFromDlq(eventIdA.toString());
        String reasonA = dlqA.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reasonA).isEqualTo("malformed_envelope");
        assertThat(dlqA.getMessageProperties().getHeaders()).doesNotContainKey("x-death"); // republish chủ động, không dead-letter
        assertPersistent(dlqA);
        // Body gốc giữ nguyên (§6): message malformed không phải JSON — so sánh raw bytes.
        assertThat(new String(dlqA.getBody(), StandardCharsets.UTF_8)).isEqualTo("this is not json");
        assertThat(inboxCount(eventIdA)).isZero();

        // (b) envelope hợp lệ nhưng schemaVersion = 2.
        UUID eventIdB = UUID.randomUUID();
        UUID correlationIdB = UUID.randomUUID();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventIdB.toString());
        env.put("eventType", "TripRequested");
        env.put("schemaVersion", 2);
        env.put("occurredAt", Instant.now().toString());
        env.put("producer", "trip-service");
        env.put("correlationId", correlationIdB.toString());
        env.put("aggregateId", UUID.randomUUID().toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", UUID.randomUUID().toString());
        payload.put("attempt", 1);
        payload.put("pickup", Map.of("latitude", 10.7769, "longitude", 106.7009));
        payload.put("excludedDriverIds", List.of());
        env.put("payload", payload);
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_REQUESTED_ROUTING_KEY,
                new Message(JSON.writeValueAsBytes(env), baseProps(eventIdB, correlationIdB, "TripRequested")));

        Message dlqB = receiveFromDlq(eventIdB.toString());
        String reasonB = dlqB.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reasonB).isEqualTo("unsupported_schema_version");
        assertThat(dlqB.getMessageProperties().getHeaders()).doesNotContainKey("x-death");
        assertPersistent(dlqB);
        assertThat(inboxCount(eventIdB)).isZero();
    }

    /** Acceptance 2 (tiếp): eventType consumer không xử lý → DLQ reason unsupported_event_type (§2). */
    @org.junit.jupiter.api.Test
    void unsupportedEventTypeGoesToDlq() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        // Envelope đúng shape nhưng eventType DriverProposed — matching-service không consume loại này.
        byte[] body = envelopeBytes(eventId, correlationId, "DriverProposed", tripId, 1);
        // Gửi thẳng vào queue (không qua exchange) để chứng minh validation chạy trên mọi message inbox.
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.MATCHING_INBOX_QUEUE, new Message(body, baseProps(eventId, correlationId, "DriverProposed")));

        Message dlq = receiveFromDlq(eventId.toString());
        String reason = dlq.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reason).isEqualTo("unsupported_event_type");
        assertThat(dlq.getMessageProperties().getHeaders()).doesNotContainKey("x-death");
        assertPersistent(dlq);
        assertThat(inboxCount(eventId)).isZero();
    }

    /** Acceptance 3: transient failure retry bounded — fail lần đầu, attempt kế tiếp thành công (§4.3). */
    @org.junit.jupiter.api.Test
    void transientFailureRetriesAndSucceeds() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        FaultInjectionConfig.FAIL_COUNTS.put(eventId, 1); // fail attempt đầu tiên chỉ

        ListAppender<ILoggingEvent> appender = attachListenerAppender();
        try {
            publishTripRequested(eventId, correlationId, tripId);
            waitForInboxRow(eventId);

            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(probeCount(eventId)).isEqualTo(1);
            // Handler chạy 2 lần: attempt 1 fail (rollback), attempt 2 commit.
            assertThat(Integer.valueOf(FaultInjectionConfig.ATTEMPTS.getOrDefault(eventId, 0))).isEqualTo(2);
            assertThat(countLogs(appender, correlationId.toString(), "inbox_retry", "failure")).isEqualTo(1);
            assertThat(countLogs(appender, correlationId.toString(), "inbox_processed", "success")).isEqualTo(1);

            assertNoDlqMessage(eventId.toString()); // retry thành công — không vào DLQ
        } finally {
            detachListenerAppender(appender);
        }
    }

    /** Acceptance 3 (tiếp): hết retry → reject(requeue=false) → DLQ chỉ x-death, KHÔNG x-dlq-reason;
     *  rollback không để inbox đánh dấu thành công (§4.3/§6b). */
    @org.junit.jupiter.api.Test
    void exhaustedRetriesGoToDlqWithoutInboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        FaultInjectionConfig.FAIL_COUNTS.put(eventId, 99); // mọi attempt đều fail

        ListAppender<ILoggingEvent> appender = attachListenerAppender();
        try {
            publishTripRequested(eventId, correlationId, tripId);
            Message dlq = receiveFromDlq(eventId.toString());

            // Broker dead-letter: có x-death, KHÔNG có x-dlq-reason (khác đường republish chủ động §6a).
            Object xDeath = dlq.getMessageProperties().getHeader("x-death");
            assertThat(xDeath).isNotNull();
            assertThat(dlq.getMessageProperties().getHeaders()).doesNotContainKey(DlqRepublisher.DLQ_REASON_HEADER);
            assertPersistent(dlq);

            // Rollback 4 lần → không có inbox row (không đánh dấu thành công).
            assertThat(inboxCount(eventId)).isZero();
            assertThat(probeCount(eventId)).isZero();

            // Attempt đầu + 3 retry = 4 execution: đúng 3 log inbox_retry failure + 1 give-up.
            assertThat(countLogs(appender, correlationId.toString(), "inbox_retry", "failure")).isEqualTo(3);
            assertThat(countLogs(appender, correlationId.toString(), "inbox_dlq", "retry_exhausted")).isEqualTo(1);
        } finally {
            detachListenerAppender(appender);
        }
    }

    // ---------- shared assertions ----------

    /** spring-amqp 4.x: delivery mode của message nhận từ broker ở getReceivedDeliveryMode(). */
    private static void assertPersistent(Message msg) {
        assertThat(msg.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

}
