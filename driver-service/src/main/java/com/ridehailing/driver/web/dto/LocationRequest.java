package com.ridehailing.driver.web.dto;

/** T09 — body PUT /api/drivers/me/location (contract §3.2). */
public record LocationRequest(Double latitude, Double longitude) {
}
