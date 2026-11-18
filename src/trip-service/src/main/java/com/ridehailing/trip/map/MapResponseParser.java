package com.ridehailing.trip.map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class MapResponseParser {
    public record GeocodedPlace(String providerPlaceId, String displayName,
                                double latitude, double longitude) {}
    public record Route(long distanceM, long durationS, String geometryJson) {}

    private final ObjectMapper json;

    public MapResponseParser(ObjectMapper json) {
        this.json = json;
    }

    public List<GeocodedPlace> parseNominatim(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (!root.isArray()) throw new IllegalArgumentException("Nominatim response must be an array.");
            List<GeocodedPlace> out = new ArrayList<>();
            for (JsonNode row : root) {
                try {
                    String id = row.path("place_id").asText();
                    String name = row.path("display_name").asText();
                    double lat = Double.parseDouble(row.path("lat").asText());
                    double lng = Double.parseDouble(row.path("lon").asText());
                    if (id.isBlank() || name.isBlank() || !Double.isFinite(lat) || !Double.isFinite(lng)
                            || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                    out.add(new GeocodedPlace(id, name, lat, lng));
                } catch (RuntimeException ignored) {
                    // Ignore individual malformed provider rows.
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed Nominatim response.", e);
        }
    }

    public Route parseOsrm(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (!"Ok".equals(root.path("code").asText()) || !root.path("routes").isArray()
                    || root.path("routes").isEmpty()) {
                throw new RouteNotFoundException("No driving route was found.");
            }
            JsonNode route = root.path("routes").get(0);
            double distance = route.path("distance").asDouble(Double.NaN);
            double duration = route.path("duration").asDouble(Double.NaN);
            JsonNode geometry = route.path("geometry");
            if (!Double.isFinite(distance) || distance <= 0 || !Double.isFinite(duration) || duration < 0
                    || !"LineString".equals(geometry.path("type").asText())
                    || !geometry.path("coordinates").isArray() || geometry.path("coordinates").size() < 2) {
                throw new IllegalArgumentException("Malformed OSRM route.");
            }
            return new Route(Math.round(distance), Math.round(duration), json.writeValueAsString(geometry));
        } catch (RouteNotFoundException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed OSRM response.", e);
        }
    }
}
