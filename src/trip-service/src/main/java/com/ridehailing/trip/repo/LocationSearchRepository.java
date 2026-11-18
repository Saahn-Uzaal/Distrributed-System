package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LocationSearchRepository {
    public record SearchResult(UUID token, UUID passengerId, String providerPlaceId, String displayName,
                               double latitude, double longitude, Instant expiresAt) {}

    private final JdbcTemplate jdbc;

    public LocationSearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SearchResult save(UUID passengerId, String providerPlaceId, String displayName,
                             double latitude, double longitude, Instant expiresAt) {
        UUID token = UUID.randomUUID();
        jdbc.update("INSERT INTO location_search_results(token,passenger_id,provider_place_id,display_name,latitude,longitude,expires_at) VALUES (?,?,?,?,?,?,?)",
                token, passengerId, providerPlaceId, displayName, latitude, longitude, Timestamp.from(expiresAt));
        return new SearchResult(token, passengerId, providerPlaceId, displayName, latitude, longitude, expiresAt);
    }

    public Optional<SearchResult> findValid(UUID token, UUID passengerId, Instant now) {
        List<SearchResult> rows = jdbc.query("SELECT token,passenger_id,provider_place_id,display_name,latitude,longitude,expires_at FROM location_search_results WHERE token=? AND passenger_id=? AND expires_at>?",
                (rs, n) -> new SearchResult((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3),
                        rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getTimestamp(7).toInstant()),
                token, passengerId, Timestamp.from(now));
        return rows.stream().findFirst();
    }
}
