package com.ridehailing.driver.service;

import com.ridehailing.driver.repo.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T09-6 (N32 driver): dọn processed_commands cũ hơn N ngày (batch hữu hạn 1000 row/chu kỳ)
 * để bảng dedup không phình vô hạn.
 */
@Component
@ConditionalOnProperty(name = {"processed-command.cleanup.enabled", "app.flyway.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ProcessedCommandCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(ProcessedCommandCleanupWorker.class);

    private final ReservationRepository reservationRepo;
    private final int retentionDays;

    public ProcessedCommandCleanupWorker(ReservationRepository reservationRepo,
                                         @Value("${processed-command.retention-days:30}") int retentionDays) {
        this.reservationRepo = reservationRepo;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${processed-command.cleanup.fixed-delay-ms:3600000}")
    public void cleanup() {
        int deleted = reservationRepo.deleteOldProcessedCommands(retentionDays);
        if (deleted > 0) {
            log.info("processed_commands cleanup deleted={} retentionDays={}", deleted, retentionDays);
        }
    }
}
