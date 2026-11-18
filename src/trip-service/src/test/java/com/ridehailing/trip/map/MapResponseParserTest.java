package com.ridehailing.trip.map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapResponseParserTest {

    private final MapResponseParser parser = new MapResponseParser(new ObjectMapper());

    @Test
    void parsesVietnamAddressResultsAndSkipsMalformedRows() {
        var results = parser.parseNominatim("""
                [
                  {"place_id":123,"display_name":"Hồ Gươm, Hà Nội, Việt Nam","lat":"21.0288","lon":"105.8525"},
                  {"place_id":124,"display_name":"bad","lat":"NaN","lon":"105.8"}
                ]
                """);

        assertThat(results).containsExactly(new MapResponseParser.GeocodedPlace(
                "123", "Hồ Gươm, Hà Nội, Việt Nam", 21.0288, 105.8525));
    }

    @Test
    void parsesOsrmRouteUsingRoadDistanceDurationAndGeoJson() {
        var route = parser.parseOsrm("""
                {"code":"Ok","routes":[{"distance":1845.6,"duration":421.2,
                  "geometry":{"type":"LineString","coordinates":[[105.84,21.03],[105.85,21.04]]}}]}
                """);

        assertThat(route.distanceM()).isEqualTo(1846);
        assertThat(route.durationS()).isEqualTo(421);
        assertThat(route.geometryJson()).isEqualTo(
                "{\"type\":\"LineString\",\"coordinates\":[[105.84,21.03],[105.85,21.04]]}");
    }

    @Test
    void rejectsOsrmResponsesWithoutARoute() {
        assertThatThrownBy(() -> parser.parseOsrm("{\"code\":\"NoRoute\",\"routes\":[]}"))
                .isInstanceOf(RouteNotFoundException.class);
    }
}
