package com.ridehailing.trip.service;

import com.ridehailing.trip.logging.AuditLog;
import com.ridehailing.trip.outbox.OutboxPublisher;
import com.ridehailing.trip.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * T13 — outbox publish worker (events.md §4.6): claim row PENDING đủ điều kiện (lease, SKIP LOCKED),
 * publish + chờ confirm rồi đánh dấu SENT; returned/lỗi transient → giữ PENDING,
 * ghi last_error và backoff next_attempt_at (retry chu kỳ kế tiếp).
 * Broker down không chặn app — mỗi chu kỳ chỉ fail với warn log, pending giữ nguyên.
 * Một row lỗi bất ngờ không dừng cả lô: lỗi được ghi vào row đó (backoff) rồi xử lý row kế tiếp.
 */
@Component
@ConditionalOnProperty(name = {"app.outbox.enabled", "app.flyway.enabled"},
        havingValue = "true", matchIfMissing = true)
public class OutboxPublishWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublishWorker.class);

    /** Lease phải dài hơn thời gian chờ confirm, để row không bị worker khác lấy lại khi đang publish. */
    private static final long LEASE_MARGIN_MS = 5_000;

    private final OutboxRepository outboxRepo;
    private final OutboxPublisher publisher;
    private final int batchSize;
    private final long leaseMs;

    public OutboxPublishWorker(OutboxRepository outboxRepo, OutboxPublisher publisher,
                               @Value("${app.outbox.batch-size:10}") int batchSize,
                               @Value("${app.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.outboxRepo = outboxRepo;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseMs = confirmTimeoutMs + LEASE_MARGIN_MS;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publish-interval-ms:1000}")
    public void publishPending() {
        for (int i = 0; i < batchSize; i++) {
            Optional<OutboxRepository.OutboxRow> rowOpt;
            try {
                rowOpt = outboxRepo.claimNextEligible(leaseMs);
            } catch (RuntimeException e) {
                // DB tạm không dùng được: bỏ chu kỳ này, chu kỳ sau thử lại (row vẫn PENDING trong DB).
                AuditLog.withMdc(AuditLog.mdc("eventType", "outbox_claim_failed", "result", "FAILURE"),
                        () -> log.warn("outbox claim failed, retry next cycle: {}", e.toString()));
                return;
            }
            if (rowOpt.isEmpty()) {
                return;
            }
            publishOne(rowOpt.get());
        }
    }

    private void publishOne(OutboxRepository.OutboxRow row) {
        Map<String, String> context = AuditLog.mdc(
                "correlationId", row.correlationId().toString(),
                "tripId", row.aggregateId().toString());
        OutboxPublisher.PublishOutcome outcome;
        try {
            outcome = publisher.publish(row);
        } catch (RuntimeException e) {
            outcome = new OutboxPublisher.PublishOutcome(false, "unexpected: " + e);
        }
        try {
            if (outcome.sent()) {
                outboxRepo.markSent(row.id());
                Map<String, String> mdc = AuditLog.mdc("eventType", "outbox_published", "result", "SUCCESS");
                mdc.putAll(context);
                AuditLog.info(log, mdc, "outbox published eventId={} eventType={} routingKey={}",
                        row.id(), row.eventType(), row.routingKey());
            } else {
                outboxRepo.recordFailure(row.id(), outcome.error(), backoffMs(row.attempts()));
                Map<String, String> mdc = AuditLog.mdc("eventType", "outbox_publish_failed", "result", "FAILURE");
                mdc.putAll(context);
                String error = outcome.error();
                AuditLog.withMdc(mdc, () -> log.warn("outbox publish failed eventId={} attempts={} error={}",
                        row.id(), row.attempts() + 1, error));
            }
        } catch (RuntimeException e) {
            // DB lỗi khi ghi kết quả: lease hết hạn thì row được claim lại (at-least-once, consumer dedup).
            Map<String, String> mdc = AuditLog.mdc("eventType", "outbox_bookkeeping_failed", "result", "FAILURE");
            mdc.putAll(context);
            AuditLog.withMdc(mdc, () -> log.error("outbox bookkeeping failed eventId={}", row.id(), e));
        }
    }

    /** Backoff tăng dần có trần: 1s, 2s, 4s, 8s, 16s rồi cap 30 s. */
    static long backoffMs(int attemptsSoFar) {
        int exp = Math.min(Math.max(attemptsSoFar, 0), 5);
        return Math.min(1000L << exp, 30_000L);
    }
}
