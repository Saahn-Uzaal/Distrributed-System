package com.ridehailing.driver.web.dto;

/** T09 — body PUT /api/drivers/me/availability (contract §3.2). */
public record AvailabilityRequest(String status) {
}
