package com.ridehailing.driver.web.dto;

import java.time.Instant;
import java.util.UUID;

/** T10 — body PUT /internal/reservations/{reservationId} (contract api-contracts.md §4.2). */
public record ReserveRequest(
        UUID commandId,
        long commandVersion,
        UUID tripId,
        UUID driverId,
        int attempt,
        Instant expiresAt,
        GeoPoint pickup,
        GeoPoint destination,
        long distanceM,
        long fareVnd) {

    public record GeoPoint(Double latitude, Double longitude) {
    }
}
