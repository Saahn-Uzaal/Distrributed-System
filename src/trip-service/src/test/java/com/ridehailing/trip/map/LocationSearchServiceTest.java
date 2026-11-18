package com.ridehailing.trip.map;

import com.ridehailing.trip.repo.LocationSearchRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationSearchServiceTest {
    @Test
    void returnsPassengerScopedOpaqueTokensForProviderResults() {
        NominatimClient client = mock(NominatimClient.class);
        LocationSearchRepository repo = mock(LocationSearchRepository.class);
        UUID passenger = UUID.randomUUID();
        when(client.search("Hồ Gươm")).thenReturn(List.of(
                new MapResponseParser.GeocodedPlace("123", "Hồ Gươm, Hà Nội, Việt Nam", 21.0288, 105.8525)));
        when(repo.save(any(), any(), any(), any(Double.class), any(Double.class), any()))
                .thenAnswer(inv -> new LocationSearchRepository.SearchResult(UUID.randomUUID(), passenger,
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5)));

        var result = new LocationSearchService(client, repo, 900).search(passenger, "  Hồ   Gươm  ");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().displayName()).isEqualTo("Hồ Gươm, Hà Nội, Việt Nam");
        assertThat(result.getFirst().locationToken()).isNotNull();
        verify(client).search("Hồ Gươm");
    }

    @Test
    void reusesCachedProviderResultsButIssuesFreshPassengerTokens() {
        NominatimClient client = mock(NominatimClient.class);
        LocationSearchRepository repo = mock(LocationSearchRepository.class);
        when(client.search("Hồ Gươm")).thenReturn(List.of(
                new MapResponseParser.GeocodedPlace("123", "Hồ Gươm, Hà Nội, Việt Nam", 21.0288, 105.8525)));
        when(repo.save(any(), any(), any(), any(Double.class), any(Double.class), any()))
                .thenAnswer(inv -> new LocationSearchRepository.SearchResult(UUID.randomUUID(), inv.getArgument(0),
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5)));
        var service = new LocationSearchService(client, repo, 900);

        var first = service.search(UUID.randomUUID(), "Hồ Gươm");
        var second = service.search(UUID.randomUUID(), "  hồ   gươm ");

        verify(client, times(1)).search("Hồ Gươm");
        assertThat(first.getFirst().locationToken()).isNotEqualTo(second.getFirst().locationToken());
    }
}
