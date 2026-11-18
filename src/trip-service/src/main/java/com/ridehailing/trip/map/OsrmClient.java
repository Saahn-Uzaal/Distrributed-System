package com.ridehailing.trip.map;

import com.ridehailing.trip.repo.LocationSearchRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OsrmClient {
    private final RestClient rest;
    private final MapResponseParser parser;

    public OsrmClient(@Qualifier("osrmRestClient") RestClient rest, MapResponseParser parser) {
        this.rest = rest;
        this.parser = parser;
    }

    public MapResponseParser.Route route(LocationSearchRepository.SearchResult pickup,
                                         LocationSearchRepository.SearchResult destination) {
        String coordinates = pickup.longitude() + "," + pickup.latitude() + ";"
                + destination.longitude() + "," + destination.latitude();
        try {
            String body = rest.get().uri(builder -> builder.path("/route/v1/driving/{coordinates}")
                    .queryParam("overview", "full").queryParam("geometries", "geojson")
                    .queryParam("steps", false).build(coordinates)).retrieve().body(String.class);
            return parser.parseOsrm(body == null ? "{}" : body);
        } catch (RouteNotFoundException e) {
            throw e;
        } catch (RestClientException | IllegalArgumentException e) {
            throw new MapUpstreamException("Routing is temporarily unavailable.", e);
        }
    }
}
