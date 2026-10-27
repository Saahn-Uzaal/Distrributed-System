package com.ridehailing.trip;

import com.ridehailing.trip.repo.SagaRepository;
import com.ridehailing.trip.service.SagaRecoveryWorker;
import com.ridehailing.trip.service.TripCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SagaRecoveryWorkerTest {
    @Test
    void failedClaimDoesNotPreventRecoveryOfOtherTripsInBatch() {
        var repo = mock(SagaRepository.class);
        var commands = mock(TripCommandService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var broken = row();
        var healthy = row();
        when(repo.findActionableDue(5)).thenReturn(List.of(broken, healthy));
        when(repo.lockByTripId(broken.tripId())).thenThrow(new IllegalStateException("temporary row failure"));
        when(repo.lockByTripId(healthy.tripId())).thenReturn(Optional.of(healthy));
        when(repo.applyLease(eq(healthy.tripId()), eq(0), eq("WAITING_DRIVER"), anyString(), eq(5000L)))
                .thenReturn(true);

        var worker = new SagaRecoveryWorker(commands, repo, manager, "test", 5000, 5);
        assertThatCode(worker::processDueSagas).doesNotThrowAnyException();
        verify(commands).handleOfferExpired(healthy.tripId());
        verify(commands, never()).handleOfferExpired(broken.tripId());
    }

    private SagaRepository.SagaRow row() {
        return new SagaRepository.SagaRow(UUID.randomUUID(), UUID.randomUUID(), "WAITING_DRIVER", "NONE",
                null, 0, null, null, "{}", 0, null);
    }
}
