package com.ridehailing.driver.web.dto;

import java.util.UUID;

public record ReleaseRequest(UUID commandId, long commandVersion, String reason) {
}
