package com.ridehailing.trip.web;

import com.ridehailing.trip.map.LocationSearchService;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.JwtAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/locations")
public class LocationController {
    private final LocationSearchService service;
    public LocationController(LocationSearchService service) { this.service = service; }

    @GetMapping("/search")
    public Map<String, List<LocationSearchService.Result>> search(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam String q) {
        return Map.of("results", service.search(user.userId(), q));
    }
}
