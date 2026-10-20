package com.ridehailing.driver.service;

import com.ridehailing.driver.repo.ReservationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded persisted scan: overdue reservations are also recovered after a restart. */
@Component
@ConditionalOnProperty(name = {"reservation.expiry.enabled", "app.flyway.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ReservationExpiryWorker {
    private final ReservationRepository repo;
    private final ReservationService service;

    public ReservationExpiryWorker(ReservationRepository repo, ReservationService service) {
        this.repo = repo;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.fixed-delay-ms:1000}")
    public void expireDue() {
        for (var id : repo.findExpiredIds(100)) {
            service.expire(id);
        }
    }
}
