package com.ridehailing.driver.web.dto;

import java.util.UUID;

public record FinishRequest(UUID commandId, long commandVersion, UUID tripId) {
}
