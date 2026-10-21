package com.ridehailing.driver.service;

import com.ridehailing.driver.repo.ReservationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T11-1 (F08) — cô lập lỗi: exception của một item không được dừng cả batch. */
class ReservationExpiryWorkerTest {

    @Test
    void failureOnOneItemDoesNotStopBatch() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ReservationRepository repo = mock(ReservationRepository.class);
        ReservationService service = mock(ReservationService.class);
        when(repo.findExpiredIds(100)).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("boom")).when(service).expireIfUnlocked(a);

        new ReservationExpiryWorker(repo, service).expireDue();

        verify(service).expireIfUnlocked(a);
        verify(service).expireIfUnlocked(b);
    }
}
