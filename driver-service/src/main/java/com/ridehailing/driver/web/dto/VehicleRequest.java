package com.ridehailing.driver.web.dto;

/** T09 — vehicle trong body profile (contract §3.2). */
public record VehicleRequest(String plateNumber, String vehicleType) {
}
