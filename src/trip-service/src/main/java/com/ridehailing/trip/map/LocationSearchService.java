package com.ridehailing.trip.map;

import com.ridehailing.trip.exception.InvalidInputException;
import com.ridehailing.trip.repo.LocationSearchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LocationSearchService {
    public record Result(UUID locationToken, String displayName, double latitude, double longitude) {}

    private final NominatimClient client;
    private final LocationSearchRepository repository;
    private final long tokenTtlSeconds;
    private final Object rateLock = new Object();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long nextRequestAtMs;
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private record CacheEntry(List<MapResponseParser.GeocodedPlace> places, long expiresAtMs) {}

    public LocationSearchService(NominatimClient client, LocationSearchRepository repository,
                                 @Value("${app.map.location-token-ttl-seconds:900}") long tokenTtlSeconds) {
        this.client = client;
        this.repository = repository;
        this.tokenTtlSeconds = Math.max(60, tokenTtlSeconds);
    }

    public List<Result> search(UUID passengerId, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().replaceAll("\\s+", " ");
        if (query.length() < 3 || query.length() > 200) {
            throw new InvalidInputException("Search query must be 3-200 characters.");
        }
        List<MapResponseParser.GeocodedPlace> places;
        String cacheKey = query.toLowerCase(Locale.ROOT);
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            CacheEntry cached = cache.get(cacheKey);
            if (cached != null && cached.expiresAtMs() > now) {
                places = cached.places();
            } else {
                if (cached != null) cache.remove(cacheKey);
                long wait = nextRequestAtMs - now;
                if (wait > 0) {
                    try { Thread.sleep(wait); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new MapUpstreamException("Address search was interrupted.", e);
                    }
                }
                // Count failed attempts too so retries cannot exceed the public Nominatim rate limit.
                nextRequestAtMs = System.currentTimeMillis() + 1000;
                places = List.copyOf(client.search(query));
                cache.put(cacheKey, new CacheEntry(places, System.currentTimeMillis() + CACHE_TTL_MS));
                if (cache.size() > MAX_CACHE_ENTRIES) {
                    cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        Instant expiresAt = Instant.now().plusSeconds(tokenTtlSeconds);
        return places.stream().limit(5).map(place -> {
            var saved = repository.save(passengerId, place.providerPlaceId(), place.displayName(),
                    place.latitude(), place.longitude(), expiresAt);
            return new Result(saved.token(), saved.displayName(), saved.latitude(), saved.longitude());
        }).toList();
    }
}
