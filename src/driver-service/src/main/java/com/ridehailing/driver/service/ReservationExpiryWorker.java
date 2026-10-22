package com.ridehailing.driver.service;

import com.ridehailing.driver.repo.ReservationRepository;
import com.ridehailing.driver.logging.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded persisted scan: overdue reservations are also recovered after a restart.
 * T11-1 (F08): mỗi item là một transaction riêng với pg_try_advisory_xact_lock —
 * item đang bị giữ được bỏ qua (không chặn hàng đợi), lỗi của một item không dừng cả batch.
 */
@Component
@ConditionalOnProperty(name = {"reservation.expiry.enabled", "app.flyway.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ReservationExpiryWorker {

    /** N11 — audit log: lỗi expire từng reservation (không ghi secret). */
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryWorker.class);

    private final ReservationRepository repo;
    private final ReservationService service;

    public ReservationExpiryWorker(ReservationRepository repo, ReservationService service) {
        this.repo = repo;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.fixed-delay-ms:1000}")
    public void expireDue() {
        for (var id : repo.findExpiredIds(100)) {
            try {
                service.expireIfUnlocked(id);
            } catch (Exception e) {
                // Cô lập lỗi từng item: log và tiếp tục với reservation còn lại.
                // L05: MDC tạm thời cho dòng lỗi, không mang eventType/result của item trước.
                AuditLog.withMdc(AuditLog.mdc("reservationId", id.toString(),
                                "eventType", "reservation_expiry_failed", "result", "FAILURE"),
                        () -> log.error("reservation expiry failed reservationId={}", id, e));
            }
        }
    }
}
