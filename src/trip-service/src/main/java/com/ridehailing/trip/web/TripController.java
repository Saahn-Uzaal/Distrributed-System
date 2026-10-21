package com.ridehailing.trip.web;

import com.ridehailing.trip.security.JwtAuthenticationFilter;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.CorrelationIdFilter;
import com.ridehailing.trip.service.TripService;
import com.ridehailing.trip.web.dto.QuoteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * T12 — public endpoints trip (contract api-contracts.md §3.3): quote, create (idempotent), list, get.
 * Auth/role đã enforce bởi SecurityFilterChain (T07-1): POST quote/create -> PASSENGER; GET mọi role xác thực.
 * Cancel/accept/reject/start/complete thuộc T18–T22 — chưa có handler ở đây.
 */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    /** POST /api/trips/quote — 200 {distanceM, fareVnd}; không tạo trip. */
    @PostMapping("/quote")
    public ResponseEntity<Map<String, Object>> quote(@RequestBody QuoteRequest req) {
        return ResponseEntity.ok(tripService.quote(req));
    }

    /** POST /api/trips — 202 + Location; Idempotency-Key bắt buộc (400 nếu thiếu/sai UUID). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId,
            @RequestBody QuoteRequest req) {
        TripService.CreateOutcome outcome = tripService.create(user, idempotencyKey, req, UUID.fromString(correlationId));
        return ResponseEntity.status(outcome.httpStatus())
                .header("Location", "/api/trips/" + outcome.body().get("tripId"))
                .body(outcome.body());
    }

    /** GET /api/trips — phân trang §1.3; passenger thấy chuyến của mình, driver thấy chuyến đã assign. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(tripService.list(user, page, size));
    }

    /** GET /api/trips/{id} — owner hoặc assigned driver; không thì 404 (chính sách §1.2). */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(tripService.get(id, user));
    }
}
