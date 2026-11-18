package com.ridehailing.trip.map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class NominatimClient {
    private final RestClient rest;
    private final MapResponseParser parser;

    public NominatimClient(@Qualifier("nominatimRestClient") RestClient rest, MapResponseParser parser) {
        this.rest = rest;
        this.parser = parser;
    }

    public List<MapResponseParser.GeocodedPlace> search(String query) {
        try {
            String body = rest.get().uri(builder -> builder.path("/search")
                    .queryParam("format", "jsonv2").queryParam("countrycodes", "vn")
                    .queryParam("limit", 5).queryParam("q", query).build()).retrieve().body(String.class);
            return parser.parseNominatim(body == null ? "[]" : body);
        } catch (RestClientException | IllegalArgumentException e) {
            throw new MapUpstreamException("Address search is temporarily unavailable.", e);
        }
    }
}
