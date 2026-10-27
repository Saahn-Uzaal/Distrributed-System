package com.ridehailing.matching.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T15 — gọi GET /internal/drivers/candidates của driver-service (contract §4.2):
 * chỉ driver AVAILABLE + heartbeat mới, sắp deterministic (distanceM tăng, tie-break driverId).
 * Matching giữ nguyên ánh xạ từ API (events.md §3.2 chốt R02) và lọc excludedDriverIds phía mình —
 * API không nhận tham số exclude. Lỗi mạng/timeout/4xx/5xx ném RuntimeException → nhánh retry
 * bounded của inbox consumer (events.md §4.3).
 */
@Component
public class DriverCandidatesClient {

    /** Candidate theo contract §4.2: driverId = profile, driverUserId = JWT sub (không suy luận bằng nhau). */
    public record Candidate(UUID driverId, UUID driverUserId, long distanceM) {
    }

    private final RestClient rest;
    private final int radiusM;
    private final int maxCandidates;

    public DriverCandidatesClient(RestClient rest,
                                  @Value("${app.matching.radius-m:5000}") int radiusM,
                                  @Value("${app.matching.max-candidates:5}") int maxCandidates) {
        this.rest = rest;
        this.radiusM = Math.max(1, radiusM);
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    public List<Candidate> findCandidates(double latitude, double longitude) {
        return findCandidates(latitude, longitude, List.of());
    }

    /**
     * T15 fix (M1): gửi excludeDriverIds để driver-service loại TRƯỚC khi áp limit. Nếu chỉ lọc sau khi
     * nhận top-N, driver bị loại trừ (thường là người gần nhất vừa từ chối) chiếm chỗ và đẩy driver rảnh
     * khác ra ngoài — có thể dẫn tới NoDriverFound dù vẫn còn driver phù hợp.
     */
    public List<Candidate> findCandidates(double latitude, double longitude, List<UUID> excludeDriverIds) {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = rest.get()
                .uri(b -> {
                    b.path("/internal/drivers/candidates")
                            .queryParam("lat", latitude)
                            .queryParam("lng", longitude)
                            .queryParam("radiusM", radiusM)
                            .queryParam("limit", maxCandidates);
                    if (!excludeDriverIds.isEmpty()) {
                        b.queryParam("excludeDriverIds", String.join(",",
                                excludeDriverIds.stream().map(UUID::toString).toList()));
                    }
                    return b.build();
                })
                .retrieve()
                .body(Map.class);
        if (root == null || !(root.get("candidates") instanceof List<?> list)) {
            throw new IllegalStateException("driver candidates response missing 'candidates' list");
        }
        List<Candidate> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            UUID driverId = parseUuid(m.get("driverId"));
            UUID driverUserId = parseUuid(m.get("driverUserId"));
            Number distanceRaw = m.get("distanceM") instanceof Number n ? n : null;
            if (driverId == null || driverUserId == null || distanceRaw == null) {
                continue; // entry hỏng — bỏ qua, không fail cả lô
            }
            out.add(new Candidate(driverId, driverUserId, Math.max(0L, distanceRaw.longValue())));
        }
        return out;
    }

    private static UUID parseUuid(Object o) {
        if (!(o instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
