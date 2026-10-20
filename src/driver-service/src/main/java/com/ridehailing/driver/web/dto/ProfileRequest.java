package com.ridehailing.driver.web.dto;

/** T09 — body POST /api/drivers/me/profile (contract §3.2). */
public record ProfileRequest(String displayName, VehicleRequest vehicle) {
}
