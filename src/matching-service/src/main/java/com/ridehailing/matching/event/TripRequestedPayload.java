package com.ridehailing.matching.event;

import java.util.List;
import java.util.UUID;

/** T14 — payload TripRequested (events.md §3.1). */
public record TripRequestedPayload(
        UUID tripId,
        int attempt,
        double pickupLatitude,
        double pickupLongitude,
        List<UUID> excludedDriverIds) {
}
