package com.ridehailing.driver.web;

import com.ridehailing.driver.security.JwtAuthenticationFilter;
import com.ridehailing.driver.security.AuthenticatedUser;
import com.ridehailing.driver.security.UnauthorizedException;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.driver.web.dto.AvailabilityRequest;
import com.ridehailing.driver.web.dto.LocationRequest;
import com.ridehailing.driver.web.dto.ProfileRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.node.NullNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T09 — public API driver (contract api-contracts.md §3.2). Auth/role DRIVER enforce bởi
 * SecurityFilterChain (T07-1: hasRole("DRIVER")); userId luôn lấy từ JWT, không tin client.
 */
@RestController
@RequestMapping("/api/drivers/me")
public class DriverController {

    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    /** State hiện tại của driver (contract §3.2) — nguồn sự thật cho UI, không có side effect. */
    @GetMapping
    public DriverService.DriverState currentState(HttpServletRequest req) {
        return service.currentState(principal(req).userId());
    }

    @PostMapping("/profile")
    public ResponseEntity<DriverService.Profile> createProfile(HttpServletRequest req,
                                                                @RequestBody ProfileRequest body) {
        AuthenticatedUser user = principal(req);
        String plateNumber = body.vehicle() == null ? null : body.vehicle().plateNumber();
        String vehicleType = body.vehicle() == null ? null : body.vehicle().vehicleType();
        DriverService.ProfileResult result =
                service.createProfile(user.userId(), body.displayName(), plateNumber, vehicleType);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.profile());
    }

    @PutMapping("/availability")
    public DriverService.AvailabilityResult setAvailability(HttpServletRequest req,
                                                             @RequestBody AvailabilityRequest body) {
        AuthenticatedUser user = principal(req);
        return service.setAvailability(user.userId(), body == null ? null : body.status());
    }

    @PutMapping("/location")
    public DriverService.LocationResult updateLocation(HttpServletRequest req,
                                                        @RequestBody LocationRequest body) {
        AuthenticatedUser user = principal(req);
        Double latitude = body == null ? null : body.latitude();
        Double longitude = body == null ? null : body.longitude();
        return service.updateLocation(user.userId(), latitude, longitude);
    }

    /**
     * T17 — offer hiện tại (contract §3.2). Có offer -> 200 + object; không có -> 200 với body JSON
     * {@code null} (contract chốt không dùng 404 cho trường hợp này). Driver chưa có profile -> 404.
     */
    @GetMapping("/offer")
    public ResponseEntity<Object> currentOffer(HttpServletRequest req) {
        AuthenticatedUser user = principal(req);
        Object body = service.currentOffer(user.userId())
                .<Object>map(offer -> offer)
                .orElse(NullNode.getInstance()); // serialize thành literal JSON null
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static AuthenticatedUser principal(HttpServletRequest req) {
        Object attr = req.getAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (!(attr instanceof AuthenticatedUser user)) {
            throw new UnauthorizedException("Missing or invalid bearer token.");
        }
        return user;
    }
}
