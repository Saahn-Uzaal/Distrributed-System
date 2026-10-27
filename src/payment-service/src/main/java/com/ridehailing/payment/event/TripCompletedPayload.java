package com.ridehailing.payment.event;

import java.util.UUID;

/** T23 — payload TripCompleted (events.md §3.4): {tripId, attempt, amountVnd, currency}. */
public record TripCompletedPayload(
        UUID tripId,
        int attempt,
        long amountVnd,
        String currency) {
}
