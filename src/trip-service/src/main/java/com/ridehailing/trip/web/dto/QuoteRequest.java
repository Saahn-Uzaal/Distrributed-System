package com.ridehailing.trip.web.dto;

/** T12 — body chung cho POST /api/trips/quote và POST /api/trips (contract §3.3). */
public record QuoteRequest(Point pickup, Point destination) {

    public record Point(Double latitude, Double longitude) {
    }
}
