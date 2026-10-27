package com.ridehailing.trip.web.dto;

/** T18/T19 — body POST /api/trips/{id}/accept|reject (contract §3.3): {"attempt": n}, phải khớp attempt của offer. */
public record AttemptRequest(Integer attempt) {
}
