package com.ridehailing.payment.service;

import com.ridehailing.payment.event.EventEnvelope;
import com.ridehailing.payment.inbox.InboxEventHandler;
import com.ridehailing.payment.repo.OutboxRepository;
import com.ridehailing.payment.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T23 — payment consumer thật (spec §20.5): consume TripCompleted → thu tiền giả lập
 * (success/failure theo cấu hình dev) → ghi payments + outbox PaymentSucceeded/PaymentFailed.
 *
 * <p>Chốt thiết kế:
 * <ul>
 *   <li>Duplicate business-intent guard (spec §9 "Unique payment attempt bảo vệ việc thu tiền
 *       giả lập"): đã có row payments cho đúng (tripId, attempt) → log + bỏ qua, ACK — KHÔNG phát
 *       event mới. Race song song giữa hai event khác eventId cùng trip/attempt vẫn được unique index
 *       {@code uq_payments_trip_attempt} trọng tài: bên thua rollback → retry/redelivery thấy row
 *       đã commit → skip.</li>
 *   <li>Ghi payments (status cuối SUCCEEDED/FAILED) + outbox PENDING trong MỘT transaction với inbox
 *       dedup record (§4.2): rollback (kể cả lỗi DB transient) → không có payments/outbox row →
 *       retry/redelivery xử lý lại từ đầu ("transaction rollback an toàn").</li>
 *   <li>Lỗi RuntimeException bất ngờ ném ra listener → nhánh retry bounded (§4.3); hết lượt → DLQ.</li>
 *   <li>Payment KHÔNG đọc Trip DB — aggregateVersion = 0, envelope bỏ field này (events.md §2 optional),
 *       như matching T15. causationId = eventId của TripCompleted (events.md §5).</li>
 * </ul>
 */
@Component
public class TripCompletedHandler implements InboxEventHandler {

    public static final String ROUTING_KEY_PAYMENT_SUCCEEDED = "payment.succeeded"; // events.md §3.5
    public static final String ROUTING_KEY_PAYMENT_FAILED = "payment.failed";       // events.md §3.6
    public static final String REASON_SIMULATED_DECLINE = "SIMULATED_DECLINE";     // events.md §3.6

    private static final Logger log = LoggerFactory.getLogger(TripCompletedHandler.class);

    private final PaymentRepository paymentRepo;
    private final OutboxRepository outboxRepo;
    private final PaymentSimulator simulator;
    private final ObjectMapper json;

    public TripCompletedHandler(PaymentRepository paymentRepo,
                                OutboxRepository outboxRepo,
                                PaymentSimulator simulator,
                                ObjectMapper json) {
        this.paymentRepo = paymentRepo;
        this.outboxRepo = outboxRepo;
        this.simulator = simulator;
        this.json = json;
    }

    /** Chạy trong transaction của listener (cùng inbox dedup insert).
     *  Lỗi transient (DB lock timeout...) ném RuntimeException → nhánh retry bounded của listener (§4.3). */
    @Override
    public void handle(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        int attempt = ((Number) p.get("attempt")).intValue();
        long amountVnd = ((Number) p.get("amountVnd")).longValue();

        // 1) Duplicate business-intent guard (spec §9): đã thu cho đúng trip/attempt → skip + ACK.
        if (paymentRepo.findByTripAndAttempt(tripId, attempt).isPresent()) {
            MDC.put("attempt", String.valueOf(attempt));
            MDC.put("eventType", "duplicate_payment_skipped");
            MDC.put("result", "SUCCESS");
            log.info("TripCompleted for already-paid trip/attempt skipped tripId={} attempt={} eventId={}",
                    tripId, attempt, envelope.eventId());
            return;
        }

        // 2) Thu tiền giả lập (spec §20.5): kết quả quyết định trong transaction này.
        UUID paymentId = UUID.randomUUID();
        if (!simulator.shouldDecline()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", tripId.toString());
            payload.put("paymentId", paymentId.toString());
            payload.put("attempt", attempt);
            payload.put("amountVnd", amountVnd);

            paymentRepo.insert(paymentId, tripId, attempt, amountVnd, "VND", "SUCCEEDED", null,
                    envelope.eventId(), envelope.correlationId());
            outboxRepo.insertPending(UUID.randomUUID(), "PaymentSucceeded", tripId, serialize(payload),
                    envelope.correlationId(), envelope.eventId(), Instant.now(), 0, ROUTING_KEY_PAYMENT_SUCCEEDED);

            MDC.put("attempt", String.valueOf(attempt));
            MDC.put("eventType", "payment_succeeded");
            MDC.put("result", "SUCCESS");
            log.info("simulated payment succeeded tripId={} attempt={} amountVnd={}", tripId, attempt, amountVnd);
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", tripId.toString());
            payload.put("paymentId", paymentId.toString());
            payload.put("attempt", attempt);
            payload.put("reason", REASON_SIMULATED_DECLINE);

            paymentRepo.insert(paymentId, tripId, attempt, amountVnd, "VND", "FAILED", REASON_SIMULATED_DECLINE,
                    envelope.eventId(), envelope.correlationId());
            outboxRepo.insertPending(UUID.randomUUID(), "PaymentFailed", tripId, serialize(payload),
                    envelope.correlationId(), envelope.eventId(), Instant.now(), 0, ROUTING_KEY_PAYMENT_FAILED);

            MDC.put("attempt", String.valueOf(attempt));
            MDC.put("eventType", "payment_failed");
            MDC.put("result", "SUCCESS"); // event phát đúng theo contract — kết quả nghiệp vụ là FAILED.
            log.info("simulated payment declined tripId={} attempt={} reason={}", tripId, attempt, REASON_SIMULATED_DECLINE);
        }
    }

    /** Serialize payload; lỗi serialize → RuntimeException (rollback + retry bounded §4.3). */
    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize payment event payload", e);
        }
    }
}
