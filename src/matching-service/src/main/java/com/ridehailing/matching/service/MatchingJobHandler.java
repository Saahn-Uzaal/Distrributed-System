package com.ridehailing.matching.service;

import com.ridehailing.matching.client.DriverCandidatesClient;
import com.ridehailing.matching.event.EventEnvelope;
import com.ridehailing.matching.inbox.InboxEventHandler;
import com.ridehailing.matching.repo.MatchingJobRepository;
import com.ridehailing.matching.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T15 — matching job thật (spec §20.4): consume TripRequested → lưu job/attempt → gọi candidates
 * driver-service → bỏ excluded drivers → Matching outbox phát DriverProposed/NoDriverFound.
 * **Không tự reserve driver** — trip-service nhận proposal và gọi reserve ở T16.
 *
 * <p>Chốt thiết kế:
 * <ul>
 *   <li>Stale-event guard (events.md §4.4): nếu đã có job của cùng trip với attempt LỚN hơn →
 *       log + bỏ qua, ACK (event hợp lệ nhưng cũ — không vào DLQ). Kiểm tra trong transaction
 *       cùng ghi job nên race giữa hai event khác attempt là an toàn.</li>
 *   <li>Ghi nghiệp vụ + outbox PENDING trong MỘT transaction với inbox dedup record (§4.2):
 *       rollback (kể cả lỗi HTTP transient) → không có job/outbox row → retry/redelivery xử lý lại.</li>
 *   <li>Lỗi gọi candidates (timeout/4xx/5xx/mạng) ném RuntimeException → nhánh retry bounded của
 *       listener (events.md §4.3); hết lượt → DLQ chỉ x-death.</li>
 *   <li>Candidates giữ nguyên thứ tự deterministic từ API (distanceM tăng, tie-break driverId —
 *       contract §4.2); matching chỉ lọc excludedDriverIds phía mình (API không nhận exclude).</li>
 * </ul>
 */
@Component
public class MatchingJobHandler implements InboxEventHandler {

    public static final String ROUTING_KEY_DRIVER_PROPOSED = "matching.proposed";   // events.md §3.2
    public static final String ROUTING_KEY_NO_DRIVER_FOUND = "matching.unavailable"; // events.md §3.3
    public static final String REASON_NO_CANDIDATES = "NO_CANDIDATES";              // events.md §3.3

    private static final Logger log = LoggerFactory.getLogger(MatchingJobHandler.class);

    private final DriverCandidatesClient candidatesClient;
    private final MatchingJobRepository jobRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper json;
    private final int maxCandidates;

    public MatchingJobHandler(DriverCandidatesClient candidatesClient,
                              MatchingJobRepository jobRepo,
                              OutboxRepository outboxRepo,
                              ObjectMapper json,
                              @Value("${app.matching.max-candidates:5}") int maxCandidates) {
        this.candidatesClient = candidatesClient;
        this.jobRepo = jobRepo;
        this.outboxRepo = outboxRepo;
        this.json = json;
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    /** Chạy trong transaction của listener (cùng inbox dedup insert).
     *  Lỗi transient (HTTP timeout/4xx/5xx/mạng, serialize hỏng) ném RuntimeException →
     *  nhánh retry bounded của listener (events.md §4.3). */
    @Override
    public void handle(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        int attempt = ((Number) p.get("attempt")).intValue();
        Map<?, ?> pickup = (Map<?, ?>) p.get("pickup");
        double latitude = ((Number) pickup.get("latitude")).doubleValue();
        double longitude = ((Number) pickup.get("longitude")).doubleValue();
        @SuppressWarnings("unchecked")
        List<UUID> excluded = ((List<?>) p.get("excludedDriverIds"))
                .stream().map(o -> UUID.fromString((String) o)).toList();

        // 1) Stale-event guard (§4.4). T15 fix (M2): khóa theo trip TRƯỚC khi đọc MAX(attempt) — dưới
        //    READ COMMITTED, hai event song song của cùng trip đều có thể đọc MAX = null rồi cùng ghi job.
        jobRepo.lockTrip(tripId);
        Integer maxAttempt = jobRepo.maxAttemptForTrip(tripId);
        if (maxAttempt != null && maxAttempt > attempt) {
            MDC.put("attempt", String.valueOf(attempt));
            MDC.put("eventType", "stale_event_skipped");
            MDC.put("result", "SUCCESS");
            log.info("stale TripRequested skipped tripId={} attempt={} maxAttempt={}", tripId, attempt, maxAttempt);
            return;
        }
        // T15 fix (M3): đã có job cho đúng attempt này (TripRequested khác eventId cho cùng attempt) →
        //    bỏ qua + ACK. Trước đây insert đụng unique (trip_id, attempt) → retry → DLQ.
        if (maxAttempt != null && maxAttempt == attempt) {
            MDC.put("attempt", String.valueOf(attempt));
            MDC.put("eventType", "duplicate_attempt_skipped");
            MDC.put("result", "SUCCESS");
            log.info("TripRequested for already-matched attempt skipped tripId={} attempt={} eventId={}",
                    tripId, attempt, envelope.eventId());
            return;
        }

        // 2) Candidates qua internal API driver-service (contract §4.2). Lỗi transient → RuntimeException
        //    → listener retry bounded (§4.3).
        //    T15 fix (M1): gửi excludedDriverIds cho driver-service để loại TRƯỚC limit; vẫn lọc lại phía mình
        //    (phòng thủ) và cắt về maxCandidates.
        List<DriverCandidatesClient.Candidate> all = candidatesClient.findCandidates(latitude, longitude, excluded);
        Set<UUID> excludedSet = Set.copyOf(excluded);
        List<DriverCandidatesClient.Candidate> chosen = new ArrayList<>(all.size());
        for (DriverCandidatesClient.Candidate c : all) {
            if (!excludedSet.contains(c.driverId()) && chosen.size() < maxCandidates) {
                chosen.add(c);
            }
        }

        // 3) Ghi job + outbox PENDING trong transaction này (§4.6). eventId outbox mới, ổn định;
        //    causationId = eventId của TripRequested (events.md §5).
        MDC.put("attempt", String.valueOf(attempt));
        if (!chosen.isEmpty()) {
            List<Map<String, Object>> candidateList = chosen.stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("driverId", c.driverId().toString());
                        m.put("driverUserId", c.driverUserId().toString());
                        m.put("distanceM", c.distanceM());
                        return m;
                    })
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", tripId.toString());
            payload.put("attempt", attempt);
            payload.put("candidates", candidateList);

            jobRepo.insert(tripId, attempt, "PROPOSED", serialize(candidateList),
                    envelope.eventId(), envelope.correlationId());
            outboxRepo.insertPending(UUID.randomUUID(), "DriverProposed", tripId,
                    serialize(payload), envelope.correlationId(), envelope.eventId(),
                    Instant.now(), 0, ROUTING_KEY_DRIVER_PROPOSED);

            MDC.put("eventType", "matching_job_created");
            MDC.put("result", "SUCCESS");
            log.info("matching job created tripId={} attempt={} state=PROPOSED candidates={}",
                    tripId, attempt, chosen.size());
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", tripId.toString());
            payload.put("attempt", attempt);
            payload.put("reason", REASON_NO_CANDIDATES);

            jobRepo.insert(tripId, attempt, "NO_DRIVER_FOUND", null,
                    envelope.eventId(), envelope.correlationId());
            outboxRepo.insertPending(UUID.randomUUID(), "NoDriverFound", tripId,
                    serialize(payload), envelope.correlationId(), envelope.eventId(),
                    Instant.now(), 0, ROUTING_KEY_NO_DRIVER_FOUND);

            MDC.put("eventType", "matching_job_created");
            MDC.put("result", "SUCCESS");
            log.info("matching job created tripId={} attempt={} state=NO_DRIVER_FOUND candidates=0",
                    tripId, attempt);
        }
    }

    /** Serialize payload; lỗi serialize → RuntimeException (rollback + retry bounded §4.3). */
    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize matching job payload", e);
        }
    }
}
