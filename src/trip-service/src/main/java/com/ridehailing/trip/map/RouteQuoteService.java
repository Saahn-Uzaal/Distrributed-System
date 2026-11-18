package com.ridehailing.trip.map;

import com.ridehailing.trip.exception.InvalidInputException;
import com.ridehailing.trip.repo.LocationSearchRepository;
import com.ridehailing.trip.repo.TripQuoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class RouteQuoteService {
    public record QuoteResult(UUID quoteToken, LocationSearchService.Result pickup,
                              LocationSearchService.Result destination, long distanceM,
                              long durationS, long fareVnd, JsonNode routeGeometry, Instant expiresAt) {}

    private final LocationSearchRepository locations;
    private final TripQuoteRepository quotes;
    private final OsrmClient osrm;
    private final ObjectMapper json;
    private final long fareBaseVnd;
    private final long farePerKmVnd;
    private final long quoteTtlSeconds;

    public RouteQuoteService(LocationSearchRepository locations, TripQuoteRepository quotes, OsrmClient osrm,
                             ObjectMapper json,
                             @Value("${fare.base-vnd:12000}") long fareBaseVnd,
                             @Value("${fare.per-km-vnd:5000}") long farePerKmVnd,
                             @Value("${app.map.quote-ttl-seconds:600}") long quoteTtlSeconds) {
        this.locations = locations; this.quotes = quotes; this.osrm = osrm; this.json = json;
        this.fareBaseVnd = fareBaseVnd; this.farePerKmVnd = farePerKmVnd;
        this.quoteTtlSeconds = Math.max(60, quoteTtlSeconds);
    }

    public QuoteResult quote(UUID passengerId, UUID pickupToken, UUID destinationToken) {
        if (pickupToken == null || destinationToken == null || pickupToken.equals(destinationToken))
            throw new InvalidInputException("Two different valid locations are required.");
        Instant now = Instant.now();
        var pickup = locations.findValid(pickupToken, passengerId, now)
                .orElseThrow(() -> new InvalidInputException("Pickup location has expired; search again."));
        var destination = locations.findValid(destinationToken, passengerId, now)
                .orElseThrow(() -> new InvalidInputException("Destination location has expired; search again."));
        var route = osrm.route(pickup, destination);
        long fare = fareBaseVnd + ((route.distanceM() + 999) / 1000) * farePerKmVnd;
        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(quoteTtlSeconds);
        quotes.insert(new TripQuoteRepository.TripQuote(quoteId, passengerId, pickup, destination,
                route.distanceM(), route.durationS(), fare, route.geometryJson(), now, expiresAt, null, null));
        return new QuoteResult(quoteId, result(pickup), result(destination), route.distanceM(), route.durationS(),
                fare, parseGeometry(route.geometryJson()), expiresAt);
    }

    private JsonNode parseGeometry(String geometryJson) {
        try {
            return json.readTree(geometryJson);
        } catch (Exception e) {
            throw new IllegalStateException("Validated route geometry could not be serialized.", e);
        }
    }

    private LocationSearchService.Result result(LocationSearchRepository.SearchResult location) {
        return new LocationSearchService.Result(location.token(), location.displayName(),
                location.latitude(), location.longitude());
    }
}
