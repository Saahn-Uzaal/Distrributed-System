package com.ridehailing.trip.service;

import com.ridehailing.trip.repo.SagaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * T21 — worker phục hồi Saga bền vững (spec §10 "Khởi động lại", state-machines.md §2 "Quy tắc worker"):
 * quét saga chưa terminal có {@code next_retry_at} đến hạn, claim bằng transaction NGẮN
 * ({@code FOR UPDATE} + CAS {@code version} + ghi {@code lease_owner}), rồi thực thi việc pending
 * NGOÀI mọi khóa DB:
 * <ul>
 *   <li>{@code WAITING_MATCH} quá deadline tổng → release mọi reservationId tất định của attempt hiện tại
 *       (tombstone) → NO_DRIVER + ABORTED;</li>
 *   <li>{@code WAITING_DRIVER} offer TTL hết hạn → release {@code OFFER_EXPIRED} → tìm lại hoặc NO_DRIVER;</li>
 *   <li>{@code CONFIRMING}/intent ACCEPT → resume confirm idempotent (orphan confirmation);</li>
 *   <li>{@code COMPENSATING}/intent REJECT|CANCEL|OFFER_EXPIRED|MATCH_DEADLINE → resume release + complete;</li>
 *   <li>{@code AWAITING_FINISH}+desired FINISH_ASSIGNMENT (T22) → resume finish idempotent
 *       sau khi trip COMPLETED nhưng driver chưa được giải phóng xác nhận.</li>
 * </ul>
 *
 * <p>Worker chết giữa chừng để lại lease cũ: điều kiện due KHÔNG yêu cầu {@code lease_owner IS NULL}, nên sau
 * khi {@code next_retry_at} (re-arm lúc claim) hết hạn saga được claim lại và intent replay idempotent
 * (commandId tất định + CAS). Request handler chạy song song an toàn: cùng commandId tất định → driver dedup
 * (§4.1); mọi completion serialize trên khóa row saga và idempotent.
 */
@Component
@ConditionalOnProperty(name = {"app.saga.enabled", "app.flyway.enabled"}, havingValue = "true", matchIfMissing = true)
public class SagaRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryWorker.class);

    private final TripCommandService commandService;
    private final SagaRepository sagaRepo;
    private final TransactionTemplate tx;
    private final String leaseOwner;
    private final long leaseMs;
    private final int batchSize;

    public SagaRecoveryWorker(TripCommandService commandService, SagaRepository sagaRepo,
                             PlatformTransactionManager transactionManager,
                             @Value("${INSTANCE_ID:saga-worker}") String instanceId,
                             @Value("${app.saga.lease-ms:5000}") long leaseMs,
                             @Value("${app.saga.batch-size:5}") int batchSize) {
        this.commandService = commandService;
        this.sagaRepo = sagaRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.leaseOwner = "trip-service-" + instanceId;
        this.leaseMs = Math.max(100, leaseMs);
        this.batchSize = Math.max(1, batchSize);
    }

    /** Chu kỳ quét: claim từng saga đến hạn rồi thực thi intent pending. Lỗi một row không dừng cả lô —
     *  lease giữ nguyên, chu kỳ kế tiếp (sau lease hết hạn) replay idempotent. */
    @Scheduled(fixedDelayString = "${app.saga.worker-interval-ms:1000}")
    public void processDueSagas() {
        List<SagaRepository.SagaRow> due;
        try {
            due = sagaRepo.findActionableDue(batchSize);
        } catch (RuntimeException e) {
            // DB tạm không dùng được: bỏ chu kỳ này, chu kỳ sau thử lại (saga vẫn nguyên trong DB).
            log.warn("saga scan failed, retry next cycle: {}", e.toString());
            return;
        }
        for (SagaRepository.SagaRow row : due) {
            try {
                if (!claim(row)) {
                    continue; // row đã đổi phase/version từ lúc quét — bên khác đang xử lý
                }
                MDC.put("correlationId", commandService.originCorrelationId(row.tripId()).toString());
                executePhase(row.tripId(), row.phase());
                tx.executeWithoutResult(s -> sagaRepo.releaseLease(row.tripId(), leaseOwner));
            } catch (RuntimeException e) {
                // Giữ lease: sau khi hết hạn, saga được claim lại và intent replay idempotent.
                log.error("saga recovery failed trip={} phase={}: {}", row.tripId(), row.phase(), e.toString());
            } finally {
                MDC.remove("correlationId");
            }
        }
    }

    /** Claim trong transaction ngắn: FOR UPDATE + re-check phase/version + CAS + ghi lease_owner
     *  + re-arm {@code next_retry_at = now + lease}. Trả false nếu worker/handler khác đã đổi row. */
    private boolean claim(SagaRepository.SagaRow row) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            SagaRepository.SagaRow locked = sagaRepo.lockByTripId(row.tripId()).orElse(null);
            if (locked == null || !row.phase().equals(locked.phase()) || locked.version() != row.version()) {
                return false;
            }
            return sagaRepo.applyLease(row.tripId(), row.version(), row.phase(), leaseOwner, leaseMs);
        }));
    }

    /** Thực thi việc pending theo phase — KHÔNG giữ khóa DB trong suốt (HTTP tới driver-service ở giữa). */
    private void executePhase(UUID tripId, String phase) {
        switch (phase) {
            case "WAITING_MATCH" -> commandService.handleWaitingMatchDeadline(tripId);
            case "WAITING_DRIVER" -> commandService.handleOfferExpired(tripId);
            case "CONFIRMING" -> commandService.resumePendingConfirm(tripId);
            case "COMPENSATING" -> commandService.resumePendingCompensation(tripId);
            case "AWAITING_FINISH" -> commandService.resumePendingFinish(tripId);
            default -> log.warn("no recovery action for saga phase {} trip={}", phase, tripId);
        }
    }
}
