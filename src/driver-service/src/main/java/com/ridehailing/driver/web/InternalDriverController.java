package com.ridehailing.driver.web;

import com.ridehailing.driver.service.DriverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * T09 — internal API candidates (contract api-contracts.md §4.2). Credential X-Internal-Token
 * enforce bởi ApiSecurityFilter (T07); matching-service gọi endpoint này ở T15.
 */
@RestController
@RequestMapping("/internal/drivers")
public class InternalDriverController {

    public record CandidatesResponse(List<DriverService.Candidate> candidates) {
    }

    private final DriverService service;

    public InternalDriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public CandidatesResponse candidates(@RequestParam double lat,
                                         @RequestParam double lng,
                                         @RequestParam(defaultValue = "5000") int radiusM,
                                         @RequestParam(defaultValue = "5") int limit,
                                         // T15 fix (M1): loại driver TRƯỚC khi áp limit — nếu matching lọc sau,
                                         // driver bị loại chiếm chỗ trong top-N và đẩy driver rảnh khác ra ngoài.
                                         @RequestParam(required = false) List<UUID> excludeDriverIds) {
        return new CandidatesResponse(service.findCandidates(lat, lng, radiusM, limit,
                excludeDriverIds == null ? List.of() : excludeDriverIds));
    }
}
