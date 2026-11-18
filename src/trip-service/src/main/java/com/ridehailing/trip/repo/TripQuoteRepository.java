package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TripQuoteRepository {
    public record TripQuote(UUID id, UUID passengerId,
                            LocationSearchRepository.SearchResult pickup,
                            LocationSearchRepository.SearchResult destination,
                            long distanceM, long durationS, long fareVnd, String routeGeometry,
                            Instant createdAt, Instant expiresAt, Instant usedAt, UUID tripId) {}

    private final JdbcTemplate jdbc;
    public TripQuoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(TripQuote q) {
        jdbc.update("INSERT INTO trip_quotes(id,passenger_id,pickup_token,destination_token,distance_m,duration_s,fare_vnd,route_geometry,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)",
                q.id(), q.passengerId(), q.pickup().token(), q.destination().token(), q.distanceM(), q.durationS(),
                q.fareVnd(), q.routeGeometry(), Timestamp.from(q.createdAt()), Timestamp.from(q.expiresAt()));
    }

    public Optional<TripQuote> findValidForUpdate(UUID id, UUID passengerId, Instant now) {
        String sql = "SELECT q.id,q.passenger_id,q.distance_m,q.duration_s,q.fare_vnd,q.route_geometry::text,q.created_at,q.expires_at,q.used_at,q.trip_id," +
                " p.token,p.provider_place_id,p.display_name,p.latitude,p.longitude,p.expires_at," +
                " d.token,d.provider_place_id,d.display_name,d.latitude,d.longitude,d.expires_at" +
                " FROM trip_quotes q JOIN location_search_results p ON p.token=q.pickup_token" +
                " JOIN location_search_results d ON d.token=q.destination_token" +
                " WHERE q.id=? AND q.passenger_id=? AND q.expires_at>? AND q.used_at IS NULL FOR UPDATE OF q";
        List<TripQuote> rows = jdbc.query(sql, (rs, n) -> {
            var pickup = new LocationSearchRepository.SearchResult((UUID) rs.getObject(11), passengerId,
                    rs.getString(12), rs.getString(13), rs.getDouble(14), rs.getDouble(15), rs.getTimestamp(16).toInstant());
            var destination = new LocationSearchRepository.SearchResult((UUID) rs.getObject(17), passengerId,
                    rs.getString(18), rs.getString(19), rs.getDouble(20), rs.getDouble(21), rs.getTimestamp(22).toInstant());
            Timestamp used = rs.getTimestamp(9);
            return new TripQuote((UUID) rs.getObject(1), (UUID) rs.getObject(2), pickup, destination,
                    rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                    rs.getTimestamp(8).toInstant(), used == null ? null : used.toInstant(), (UUID) rs.getObject(10));
        }, id, passengerId, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public int markUsed(UUID id, UUID tripId, Instant now) {
        return jdbc.update("UPDATE trip_quotes SET used_at=?,trip_id=? WHERE id=? AND used_at IS NULL",
                Timestamp.from(now), tripId, id);
    }
}
