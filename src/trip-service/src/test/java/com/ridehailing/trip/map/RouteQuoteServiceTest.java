package com.ridehailing.trip.map;

import com.ridehailing.trip.repo.LocationSearchRepository;
import com.ridehailing.trip.repo.TripQuoteRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

class RouteQuoteServiceTest {
    @Test
    void fareUsesOsrmRoadDistanceAndReturnsPersistedQuoteToken() {
        UUID passenger = UUID.randomUUID();
        UUID pickupToken = UUID.randomUUID();
        UUID destinationToken = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(900);
        var pickup = new LocationSearchRepository.SearchResult(pickupToken, passenger, "1", "Hồ Gươm", 21.0288, 105.8525, expiry);
        var destination = new LocationSearchRepository.SearchResult(destinationToken, passenger, "2", "Lăng Bác", 21.0368, 105.8347, expiry);
        LocationSearchRepository locations = mock(LocationSearchRepository.class);
        when(locations.findValid(eq(pickupToken), eq(passenger), any())).thenReturn(Optional.of(pickup));
        when(locations.findValid(eq(destinationToken), eq(passenger), any())).thenReturn(Optional.of(destination));
        OsrmClient osrm = mock(OsrmClient.class);
        when(osrm.route(pickup, destination)).thenReturn(new MapResponseParser.Route(1846, 421,
                "{\"type\":\"LineString\",\"coordinates\":[[105.8525,21.0288],[105.8347,21.0368]]}"));
        TripQuoteRepository quotes = mock(TripQuoteRepository.class);

        var result = new RouteQuoteService(locations, quotes, osrm, new ObjectMapper(), 12000, 5000, 600)
                .quote(passenger, pickupToken, destinationToken);

        assertThat(result.distanceM()).isEqualTo(1846);
        assertThat(result.durationS()).isEqualTo(421);
        assertThat(result.fareVnd()).isEqualTo(22000);
        assertThat(result.quoteToken()).isNotNull();
        assertThat(result.routeGeometry().path("type").asText()).isEqualTo("LineString");
    }
}
