package com.ridehailing.trip.service;

import com.ridehailing.trip.repo.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T12-3 (N32 trip): dọn idempotency_keys cũ hơn N giờ (batch hữu hạn 1000 row/chu kỳ)
 * để bảng replay không phình vô hạn.
 */
@Component
@ConditionalOnProperty(name = {"idempotency-key.cleanup.enabled", "app.flyway.enabled"},
        havingValue = "true", matchIfMissing = true)
public class IdempotencyKeyCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanupWorker.class);

    private final IdempotencyKeyRepository idempotencyRepo;
    private final int retentionHours;

    public IdempotencyKeyCleanupWorker(IdempotencyKeyRepository idempotencyRepo,
                                      @Value("${idempotency-key.retention-hours:24}") int retentionHours) {
        this.idempotencyRepo = idempotencyRepo;
        this.retentionHours = retentionHours;
    }

    @Scheduled(fixedDelayString = "${idempotency-key.cleanup.fixed-delay-ms:3600000}")
    public void cleanup() {
        int deleted = idempotencyRepo.deleteOldKeys(retentionHours);
        if (deleted > 0) {
            log.info("idempotency_keys cleanup deleted={} retentionHours={}", deleted, retentionHours);
        }
    }
}
