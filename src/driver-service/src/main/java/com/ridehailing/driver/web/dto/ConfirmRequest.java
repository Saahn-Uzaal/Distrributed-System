package com.ridehailing.driver.web.dto;

import java.util.UUID;

public record ConfirmRequest(UUID commandId, long commandVersion, UUID tripId, UUID driverId, int attempt) {
}
