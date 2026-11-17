# Address Map and Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw coordinate entry with Vietnam address search, server-authoritative OSRM quotes, Leaflet route previews, and matching route/address details for passengers and drivers.

**Architecture:** `trip-service` owns the Nominatim/OSRM adapters, persists short-lived address selections and quotes, and creates trips only from a valid quote token. Route/address snapshots are propagated through the existing TripRequested → reservation flow so the driver UI never needs to call a public map API.

**Tech Stack:** Java 21, Spring Boot 4.1.1 `RestClient`, JdbcTemplate, PostgreSQL/Flyway, RabbitMQ, React 18, TypeScript, Ant Design, Leaflet 1.9.4, React Leaflet 4.2.1, Vitest, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-17-address-map-routing-design.md`

## Global Constraints

- Search only within Vietnam and only after an explicit **Tìm**/Enter action; never query Nominatim per keystroke.
- Keep aggregate Nominatim traffic at or below 1 request/second, identify the application with a non-stock `User-Agent`, cache repeated searches, and show OpenStreetMap attribution.
- Public Nominatim/OSRM are demo dependencies only; endpoints and timeouts must be environment-configurable.
- Do not fall back to Haversine when geocoding or routing fails; no valid route means no quote and no trip creation.
- The server is the only authority for distance, duration, geometry, and fare.
- CI tests must use HTTP stubs and must not call public mapping services.
- Existing idempotency, concurrency, Saga, recovery, and security behavior must remain intact.
- Do not edit existing Flyway migrations; add `V9` to trip-service and `V6` to driver-service.

---

## File Structure

### Backend: trip-service

- Create `web/LocationController.java`: authenticated passenger address search endpoint.
- Create `web/dto/LocationSearchResponse.java`, `web/dto/RouteQuoteRequest.java`, `web/dto/CreateTripRequest.java`: stable public contracts.
- Create `map/NominatimClient.java`, `map/OsrmClient.java`, `map/MapHttpClientConfig.java`: provider-specific HTTP adapters and clients.
- Create `map/LocationSearchService.java`, `map/RouteQuoteService.java`, `map/MapUpstreamException.java`: provider-independent orchestration, validation, rate limiting, and fare calculation.
- Create `repo/LocationSearchRepository.java`, `repo/TripQuoteRepository.java`: durable selection tokens and quote consumption.
- Create `db/migration/V9__address_route_quotes.sql`: search selections, quote table, and trip snapshot columns.
- Modify `TripController`, `TripService`, `TripRepository`, `SecurityConfig`, `GlobalExceptionHandler`, `application.yml`: use quote tokens and expose snapshots.
- Modify `TripInboxHandler` and retry payload builders in `TripCommandService`: propagate address/route snapshot in reservation commands.

### Backend: driver-service

- Create `db/migration/V6__reservation_route_snapshot.sql`: reservation address/duration/geometry columns.
- Modify `ReserveRequest`, `ReservationRepository`, `ReservationService`, and `DriverService.Offer`: accept, persist, validate, and return the route snapshot.

### Frontend and edge

- Create `src/web/src/maps/RouteMap.tsx`: reusable Leaflet map that renders pickup, destination, and GeoJSON route.
- Create `src/web/src/maps/AddressSearchField.tsx`: explicit search-and-select control with no keystroke autocomplete.
- Create focused tests beside both map components.
- Modify trip/driver types, API clients, `CreateTripForm`, `OfferPanel`, `BusyTripPanel`, trip list/detail views, Vite proxy, and Nginx route.
- Modify `docs/openapi.yaml`, `.env.example`, and `deploy/compose.yaml` to document and configure the integration.

---

### Task 1: Persist Search Selections, Quotes, and Trip Route Snapshots

**Files:**
- Create: `src/trip-service/src/main/resources/db/migration/V9__address_route_quotes.sql`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/repo/LocationSearchRepository.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/repo/TripQuoteRepository.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/repo/TripRepository.java`
- Test: `src/trip-service/src/test/java/com/ridehailing/trip/MapQuoteRepositoryIntegrationTest.java`

**Interfaces:**
- Produces `LocationSearchRepository.save(UUID passengerId, List<SearchResult> results, Instant expiresAt)` and `findValid(UUID token, UUID passengerId, Instant now)`.
- Produces `TripQuoteRepository.insert(TripQuote quote)`, `findValidForUpdate(UUID token, UUID passengerId, Instant now)`, and `markUsed(UUID token, UUID tripId, Instant usedAt)`.
- Expands `TripRepository.TripRow` with `pickupAddress`, `destinationAddress`, `routeDurationS`, and `routeGeometry`.

- [ ] **Step 1: Write the failing repository integration test**

Add tests that migrate a real PostgreSQL container, save two location selections, create a quote, reject lookup by another passenger, reject an expired quote, lock a valid quote, consume it once, and read the route snapshot back from a trip. Use values:

```java
UUID passenger = UUID.randomUUID();
UUID pickupToken = UUID.randomUUID();
String geometry = "{\"type\":\"LineString\",\"coordinates\":[[105.84,21.03],[105.85,21.04]]}";
assertThat(quoteRepo.findValidForUpdate(quoteId, passenger, now)).isPresent();
assertThat(quoteRepo.markUsed(quoteId, tripId, now)).isEqualTo(1);
assertThat(quoteRepo.markUsed(quoteId, tripId, now)).isZero();
```

- [ ] **Step 2: Run the test and verify migration/repositories are missing**

Run: `./mvnw -pl src/trip-service -Dtest=MapQuoteRepositoryIntegrationTest test`

Expected: compilation failure because the repositories and migration-backed schema do not exist.

- [ ] **Step 3: Add the migration**

Create `location_search_results` keyed by opaque UUID token with passenger, provider place ID, display name, coordinates, created/expiry timestamps; create `trip_quotes` with passenger, both selected locations, distance, duration, fare, GeoJSON geometry, created/expiry/used timestamps and optional created trip ID. Add non-null address, duration, and JSONB route geometry columns to `trips`, backfilling existing rows with coordinate labels, duration `0`, and an empty LineString before applying `NOT NULL`.

- [ ] **Step 4: Implement focused JdbcTemplate repositories and expand TripRepository**

Use records with explicit types:

```java
public record SearchResult(UUID token, UUID passengerId, String providerPlaceId,
        String displayName, double latitude, double longitude, Instant expiresAt) {}

public record TripQuote(UUID id, UUID passengerId, SearchResult pickup, SearchResult destination,
        long distanceM, long durationS, long fareVnd, String routeGeometry,
        Instant createdAt, Instant expiresAt, Instant usedAt, UUID tripId) {}
```

Keep SQL mapping and conditional consumption inside the repositories. Change `TripRepository.insert(...)` to accept all route snapshot fields.

- [ ] **Step 5: Run repository tests**

Run: `./mvnw -pl src/trip-service -Dtest=MapQuoteRepositoryIntegrationTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/trip-service/src/main/resources/db/migration/V9__address_route_quotes.sql src/trip-service/src/main/java/com/ridehailing/trip/repo src/trip-service/src/test/java/com/ridehailing/trip/MapQuoteRepositoryIntegrationTest.java
git commit -m "feat: persist route quotes and trip snapshots"
```

### Task 2: Add Policy-Compliant Vietnam Address Search

**Files:**
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/MapHttpClientConfig.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/NominatimClient.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/LocationSearchService.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/MapUpstreamException.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/web/LocationController.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/web/dto/LocationSearchResponse.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/config/SecurityConfig.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/exception/GlobalExceptionHandler.java`
- Modify: `src/trip-service/src/main/resources/application.yml`
- Test: `src/trip-service/src/test/java/com/ridehailing/trip/LocationSearchIntegrationTest.java`

**Interfaces:**
- Produces `LocationSearchService.search(UUID passengerId, String query): LocationSearchResponse`.
- Public endpoint: `GET /api/locations/search?q=...` for role `PASSENGER`.
- Response entries contain `locationToken`, `displayName`, `latitude`, `longitude`; provider IDs remain internal.

- [ ] **Step 1: Write failing HTTP integration tests using a loopback stub**

Cover: unauthenticated 401, DRIVER 403, blank/short query 400, `countrycodes=vn`, `limit=5`, identifying User-Agent, provider mapping, repeated normalized query served from cache, and upstream timeout mapped to `503 MAP_SERVICE_UNAVAILABLE` with correlation ID.

```java
mockNominatim.respond(200, """
  [{"place_id":123,"display_name":"Hồ Gươm, Hà Nội, Việt Nam","lat":"21.0288","lon":"105.8525"}]
  """);
get("/api/locations/search?q=Hồ%20Gươm", passengerToken)
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.results[0].locationToken").isString());
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./mvnw -pl src/trip-service -Dtest=LocationSearchIntegrationTest test`

Expected: 404/compilation failures for missing endpoint and types.

- [ ] **Step 3: Implement dedicated Nominatim RestClient**

Configure `nominatimRestClient` with `${MAP_NOMINATIM_BASE_URL:https://nominatim.openstreetmap.org}`, connect/read timeouts, and `${MAP_USER_AGENT:DS03-RideHailing/0.1 (local-demo)}`. Request `/search?format=jsonv2&countrycodes=vn&limit=5&q=...`; parse only required fields and reject malformed/out-of-range provider data.

- [ ] **Step 4: Implement service rate limiting, caching, and durable result tokens**

Normalize query with trim + whitespace collapse + lowercase. Use a synchronized one-request-per-second gate around cache misses, and a bounded in-memory cache with 15-minute TTL and at most 500 query keys. On a provider result, save passenger-scoped opaque location tokens with a 15-minute expiry. Cache provider results, not passenger tokens, so each caller receives tokens owned by that caller.

- [ ] **Step 5: Add endpoint, authorization, and stable error mapping**

Add passenger-only `GET /api/locations/search`. Map provider transport/5xx/malformed responses to:

```json
{"code":"MAP_SERVICE_UNAVAILABLE","message":"Dịch vụ tìm địa chỉ tạm thời không khả dụng.","correlationId":"..."}
```

- [ ] **Step 6: Run search and security tests**

Run: `./mvnw -pl src/trip-service -Dtest=LocationSearchIntegrationTest,SecurityProbeTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/trip-service/src/main/java/com/ridehailing/trip/map src/trip-service/src/main/java/com/ridehailing/trip/web src/trip-service/src/main/java/com/ridehailing/trip/config/SecurityConfig.java src/trip-service/src/main/java/com/ridehailing/trip/exception/GlobalExceptionHandler.java src/trip-service/src/main/resources/application.yml src/trip-service/src/test/java/com/ridehailing/trip/LocationSearchIntegrationTest.java
git commit -m "feat: add Vietnam address search"
```

### Task 3: Generate Server-Authoritative OSRM Quotes

**Files:**
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/OsrmClient.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/map/RouteQuoteService.java`
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/web/dto/RouteQuoteRequest.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/web/TripController.java`
- Modify: `src/trip-service/src/main/resources/application.yml`
- Test: `src/trip-service/src/test/java/com/ridehailing/trip/RouteQuoteIntegrationTest.java`

**Interfaces:**
- `RouteQuoteRequest(UUID pickupLocationToken, UUID destinationLocationToken)`.
- `RouteQuoteService.quote(UUID passengerId, RouteQuoteRequest request)` returns `quoteToken`, both locations, `distanceM`, `durationS`, `fareVnd`, GeoJSON LineString, and `expiresAt`.
- Replaces the old coordinate-based `POST /api/trips/quote` contract.

- [ ] **Step 1: Write failing quote integration tests**

Test a successful OSRM response, token ownership, expired/missing location token, no route, timeout, malformed geometry, and fare rounding:

```json
{"code":"Ok","routes":[{"distance":1845.6,"duration":421.2,"geometry":{"type":"LineString","coordinates":[[105.84,21.03],[105.85,21.04]]}}]}
```

Assert `distanceM=1846`, `durationS=421`, and `fareVnd=22000` for base `12000` plus two started kilometers at `5000`.

- [ ] **Step 2: Run and observe failure**

Run: `./mvnw -pl src/trip-service -Dtest=RouteQuoteIntegrationTest test`

Expected: old endpoint expects coordinates and no OSRM client exists.

- [ ] **Step 3: Implement the OSRM adapter**

Use its own `RestClient` configured by `${MAP_OSRM_BASE_URL:https://router.project-osrm.org}` and `${MAP_OSRM_TIMEOUT_MS:5000}`. Request:

```text
/route/v1/driving/{pickupLng},{pickupLat};{destinationLng},{destinationLat}?overview=full&geometries=geojson&steps=false
```

Accept only `code=Ok`, one route, positive finite distance/duration, and a valid LineString with finite coordinate pairs.

- [ ] **Step 4: Implement quote orchestration and persistence**

Resolve both location tokens for the authenticated passenger, call OSRM outside a database transaction, compute fare server-side, serialize the validated geometry, and insert a quote expiring after `${MAP_QUOTE_TTL_SECONDS:600}`.

- [ ] **Step 5: Replace controller contract and map failures**

Pass the authenticated passenger into the quote service. Return `422 ROUTE_NOT_FOUND` for a clean OSRM `NoRoute`; return `503 MAP_SERVICE_UNAVAILABLE` for transport/provider failures.

- [ ] **Step 6: Run quote tests**

Run: `./mvnw -pl src/trip-service -Dtest=RouteQuoteIntegrationTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/trip-service/src/main/java/com/ridehailing/trip/map src/trip-service/src/main/java/com/ridehailing/trip/web src/trip-service/src/main/resources/application.yml src/trip-service/src/test/java/com/ridehailing/trip/RouteQuoteIntegrationTest.java
git commit -m "feat: quote trips from OSRM routes"
```

### Task 4: Create Trips Atomically from Quote Tokens

**Files:**
- Create: `src/trip-service/src/main/java/com/ridehailing/trip/web/dto/CreateTripRequest.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/service/TripService.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/web/TripController.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/service/TripInboxHandler.java`
- Modify: `src/trip-service/src/main/java/com/ridehailing/trip/service/TripCommandService.java`
- Test: `src/trip-service/src/test/java/com/ridehailing/trip/TripIntegrationTest.java`
- Test: `src/trip-service/src/test/java/com/ridehailing/trip/TripProposalIntegrationTest.java`

**Interfaces:**
- `CreateTripRequest(UUID quoteToken)`.
- `TripService.create(AuthenticatedUser user, String idempotencyKey, CreateTripRequest request, UUID correlationId)`.
- `TripRequested` and reserve bodies add `pickupAddress`, `destinationAddress`, `routeDurationS`, and `routeGeometry`; retain pickup/destination coordinates, `distanceM`, and `fareVnd`.

- [ ] **Step 1: Update integration tests first**

Create valid quotes in test setup, then cover successful creation, wrong owner, expired token, reuse by a new idempotency key, concurrent consumption, same-key replay, same-key/different-token mismatch, and unchanged active-trip enforcement. Assert trip detail and outbox payload contain the saved snapshot exactly.

- [ ] **Step 2: Run tests and verify the old coordinate contract fails**

Run: `./mvnw -pl src/trip-service -Dtest=TripIntegrationTest,TripProposalIntegrationTest test`

Expected: FAIL until create consumes quote tokens.

- [ ] **Step 3: Implement transactional quote consumption**

Use the canonical request hash `quoteToken=<uuid>`. Inside the existing create transaction and passenger advisory lock, lock the quote row, validate ownership/expiry/unused state, insert the trip snapshot, mark the quote used with a conditional update, then create outbox/event/saga rows. Preserve replay ordering so a successfully stored idempotency response is returned even though the quote is now used.

- [ ] **Step 4: Expand every TripRequested reconstruction path**

Update initial publish, retry-after-no-driver, reject/expiry retries, and Saga recovery payloads to include the identical address/route snapshot. Do not recompute routes.

- [ ] **Step 5: Return route fields in list/detail APIs**

Add `pickup.address`, `destination.address`, `routeDurationS`, and `routeGeometry` while retaining coordinates for map rendering and matching.

- [ ] **Step 6: Run focused trip tests**

Run: `./mvnw -pl src/trip-service -Dtest=TripIntegrationTest,TripProposalIntegrationTest,TripSagaRecoveryIntegrationTest,TripRejectIntegrationTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/trip-service/src/main/java src/trip-service/src/test/java
git commit -m "feat: create trips from route quote tokens"
```

### Task 5: Propagate Route Snapshots into Driver Reservations

**Files:**
- Create: `src/driver-service/src/main/resources/db/migration/V6__reservation_route_snapshot.sql`
- Modify: `src/driver-service/src/main/java/com/ridehailing/driver/web/dto/ReserveRequest.java`
- Modify: `src/driver-service/src/main/java/com/ridehailing/driver/repo/ReservationRepository.java`
- Modify: `src/driver-service/src/main/java/com/ridehailing/driver/service/ReservationService.java`
- Modify: `src/driver-service/src/main/java/com/ridehailing/driver/service/DriverService.java`
- Modify: `src/driver-service/src/test/java/com/ridehailing/driver/ReserveIntegrationTest.java`
- Modify: `src/driver-service/src/test/java/com/ridehailing/driver/DriverOfferIntegrationTest.java`
- Modify: `src/driver-service/src/test/java/com/ridehailing/driver/service/ReservationServiceHashTest.java`

**Interfaces:**
- Expands `ReserveRequest` and `DriverService.Offer` with `pickupAddress`, `destinationAddress`, `routeDurationS`, `routeGeometry`.
- Reservation command hash includes every new immutable snapshot field.

- [ ] **Step 1: Write failing reserve and offer assertions**

Extend request fixtures and assert the GET offer response reproduces exact Unicode Vietnamese addresses and GeoJSON. Add a hash test proving that changing any address/duration/geometry under the same command ID is rejected as a command mismatch.

- [ ] **Step 2: Run driver tests and observe failure**

Run: `./mvnw -pl src/driver-service -Dtest=ReserveIntegrationTest,DriverOfferIntegrationTest,ReservationServiceHashTest test`

Expected: compilation/assertion failures for missing fields.

- [ ] **Step 3: Add migration and persistence mapping**

Add non-null route snapshot columns to active reservations, backfill legacy rows with coordinate labels/empty geometry, and update insert/select mappings.

- [ ] **Step 4: Validate and hash the full immutable command**

Require non-blank addresses (max 500 characters), nonnegative duration, and a valid size-bounded geometry string. Include normalized exact field values in `CommandHash` input so replay semantics remain strict.

- [ ] **Step 5: Return the snapshot from the offer endpoint**

Update `DriverService.Offer` construction without any call to OSRM/Nominatim.

- [ ] **Step 6: Run driver tests**

Run: `./mvnw -pl src/driver-service -Dtest=ReserveIntegrationTest,DriverOfferIntegrationTest,ReservationServiceHashTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/driver-service/src/main src/driver-service/src/test
git commit -m "feat: expose route snapshots in driver offers"
```

### Task 6: Add Frontend Map Domain Types, API Client, and Reusable Route Map

**Files:**
- Modify: `src/web/package.json`
- Modify: `src/web/package-lock.json`
- Modify: `src/web/src/trips/types.ts`
- Modify: `src/web/src/drivers/types.ts`
- Modify: `src/web/src/api/trips.ts`
- Create: `src/web/src/maps/RouteMap.tsx`
- Create: `src/web/src/maps/RouteMap.test.tsx`
- Modify: `src/web/src/test/setup.ts`

**Interfaces:**
- `LocationSelection { locationToken, displayName, latitude, longitude }`.
- `RouteGeometry { type: 'LineString'; coordinates: [number, number][] }`.
- `QuoteResult { quoteToken, pickup, destination, distanceM, durationS, fareVnd, routeGeometry, expiresAt }`.
- `RouteMapProps { pickup, destination, geometry, height?: number }`.
- `tripsApi.searchLocations(query, signal)`, `quote(pickupLocationToken, destinationLocationToken, signal)`, `createTrip(key, quoteToken, signal)`.

- [ ] **Step 1: Install compatible Leaflet dependencies**

Run:

```bash
cd src/web
npm install leaflet@1.9.4 react-leaflet@4.2.1
npm install --save-dev @types/leaflet@1.9.20
```

Expected: lockfile updated without changing React major version.

- [ ] **Step 2: Write failing type/API/map component tests**

Assert exact request bodies and URLs. For `RouteMap`, mock React Leaflet components and assert marker positions use `[latitude, longitude]` while GeoJSON coordinates remain `[longitude, latitude]`; assert OpenStreetMap attribution is configured.

- [ ] **Step 3: Run tests and verify failure**

Run: `cd src/web && npm test -- src/api/trips.test.ts src/maps/RouteMap.test.tsx`

Expected: FAIL for missing types/component and old API bodies.

- [ ] **Step 4: Implement types and API calls**

Use URL encoding for `q`; reject no data in the UI layer, not by fabricating coordinates. Update trip/offer types so `pickup` and `destination` contain `address`, `latitude`, and `longitude`.

- [ ] **Step 5: Implement RouteMap**

Import Leaflet CSS once, configure marker icons compatible with Vite, render an OpenStreetMap tile layer, two labeled markers, a GeoJSON route, and fit bounds whenever route/points change. Render a stable placeholder when geometry is absent so tests and mobile layout do not jump.

- [ ] **Step 6: Run tests and build**

Run: `cd src/web && npm test -- src/api/trips.test.ts src/maps/RouteMap.test.tsx && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/web/package.json src/web/package-lock.json src/web/src
git commit -m "feat: add route map frontend primitives"
```

### Task 7: Replace Passenger Coordinate Form with Address Search and Quote Review

**Files:**
- Create: `src/web/src/maps/AddressSearchField.tsx`
- Create: `src/web/src/maps/AddressSearchField.test.tsx`
- Modify: `src/web/src/pages/passenger/CreateTripForm.tsx`
- Modify: `src/web/src/pages/passenger/CreateTripForm.test.tsx`
- Modify: `src/web/src/pages/passenger/TripsListPanel.tsx`
- Modify: `src/web/src/pages/TripDetailPage.tsx`

**Interfaces:**
- `AddressSearchField` calls `onSelect(LocationSelection)` only after an explicit search result click.
- `CreateTripForm` owns pickup/destination selections and quote state; any selection change clears quote and pending idempotency key.

- [ ] **Step 1: Write failing interaction tests**

Cover explicit Enter/button search, no request while typing, zero results, selecting pickup/destination, requesting quote, rendering route/duration/fare, disabling create before quote, invalidating quote after a selection change, quote expiry, mapping outage, and ambiguous create replay preserving the same idempotency key and quote token.

```tsx
fireEvent.change(screen.getByLabelText('Điểm đón'), { target: { value: 'Hồ Gươm' } })
expect(fetchMock).not.toHaveBeenCalled()
fireEvent.click(screen.getByRole('button', { name: 'Tìm điểm đón' }))
```

- [ ] **Step 2: Run tests and verify the coordinate UI fails expectations**

Run: `cd src/web && npm test -- src/maps/AddressSearchField.test.tsx src/pages/passenger/CreateTripForm.test.tsx`

Expected: FAIL because the current form has four spinbuttons.

- [ ] **Step 3: Implement AddressSearchField**

Use an Ant Design input plus explicit button, loading/error/empty states, and a selectable result list. Do not add `onChange` API calls, timers, or autocomplete behavior. Preserve full accessible labels.

- [ ] **Step 4: Implement the passenger quote-review flow**

Render two search controls and `RouteMap`. After both selections, enable **Xem tuyến đường và giá**. On success show distance in km, duration in rounded minutes, VND price, expiration, and **Đặt chuyến**. Clear stale quote synchronously whenever either location changes. Keep existing ambiguous-outcome idempotency behavior.

- [ ] **Step 5: Replace raw coordinates in passenger trip list/detail**

Show address strings primarily; keep coordinates only inside map props. Add route map to detail, not every list row, to avoid excessive tile loading.

- [ ] **Step 6: Run passenger tests and build**

Run: `cd src/web && npm test -- src/maps/AddressSearchField.test.tsx src/pages/passenger/CreateTripForm.test.tsx src/pages/TripDetailPage.test.tsx && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/web/src/maps src/web/src/pages/passenger src/web/src/pages/TripDetailPage.tsx src/web/src/pages/TripDetailPage.test.tsx
git commit -m "feat: let passengers search and review routes"
```

### Task 8: Show Route Maps and Addresses to Drivers

**Files:**
- Modify: `src/web/src/pages/driver/OfferPanel.tsx`
- Modify: `src/web/src/pages/driver/BusyTripPanel.tsx`
- Modify: `src/web/src/pages/DriverHome.test.tsx`
- Modify: `src/web/src/pages/TripDetailPage.test.tsx`

**Interfaces:**
- Consumes the `Offer` and `Trip` route snapshot fields from Tasks 5 and 6.
- Uses `RouteMap` without any geocoding/routing network call.

- [ ] **Step 1: Write failing driver UI assertions**

Update fixtures with Vietnamese addresses, duration, and geometry. Assert offer and active-trip views render both address labels, rounded travel time, route map, price, and unchanged action buttons/countdown behavior.

- [ ] **Step 2: Run and observe failures**

Run: `cd src/web && npm test -- src/pages/DriverHome.test.tsx src/pages/TripDetailPage.test.tsx`

Expected: FAIL because coordinates are still shown and no map exists.

- [ ] **Step 3: Update OfferPanel and BusyTripPanel**

Render `RouteMap` once per visible panel, full address text with safe wrapping, distance/duration/fare descriptions, and the existing receive/reject/start/complete controls. Do not refetch the route during polling.

- [ ] **Step 4: Run driver tests and full frontend suite**

Run: `cd src/web && npm test && npm run build`

Expected: all frontend tests and TypeScript build PASS.

- [ ] **Step 5: Commit**

```bash
git add src/web/src/pages/driver src/web/src/pages/DriverHome.test.tsx src/web/src/pages/TripDetailPage.test.tsx
git commit -m "feat: show routes in driver trip views"
```

### Task 9: Wire Edge Routing, Runtime Configuration, and API Documentation

**Files:**
- Modify: `deploy/nginx.conf`
- Modify: `src/web/vite.config.ts`
- Modify: `deploy/compose.yaml`
- Modify: `.env.example`
- Modify: `docs/openapi.yaml`
- Modify: `README.md`
- Test: `tests/e2e/ui-flow.cjs`

**Interfaces:**
- `/api/locations` and `/api/locations/**` proxy to trip-service in dev and production.
- Compose passes `MAP_NOMINATIM_BASE_URL`, `MAP_OSRM_BASE_URL`, `MAP_USER_AGENT`, `MAP_HTTP_TIMEOUT_MS`, `MAP_OSRM_TIMEOUT_MS`, and `MAP_QUOTE_TTL_SECONDS`.

- [ ] **Step 1: Add failing edge/E2E expectations**

Update E2E mocks or network interception so the browser searches two addresses, selects results, receives a quote geometry, sees price/map, creates with `quoteToken`, and verifies the driver sees the same addresses. Add an Nginx check that `/api/locations/search` is proxied while `/internal` remains 404.

- [ ] **Step 2: Add Nginx and Vite routes**

Mirror the exact `/api/trips` proxy protections for `/api/locations`, including body size, correlation ID, JSON error pages, and upstream timeouts.

- [ ] **Step 3: Add environment configuration**

Use public defaults only in `.env.example`; pass them explicitly in compose. Do not add API keys. Document that public services are for local/demo use and can refuse traffic.

- [ ] **Step 4: Update OpenAPI and README**

Document search results, location tokens, quote tokens, route geometry, duration, all new errors, updated create body, and map attribution/policy limitations. Replace raw coordinate examples in the primary demo flow with address search.

- [ ] **Step 5: Run static and E2E verification**

Run:

```bash
docker compose --env-file .env -f deploy/compose.yaml config --quiet
cd src/web && npm run build
cd ../.. && node tests/e2e/ui-flow.cjs
```

Expected: compose validation and E2E PASS against the rebuilt stack.

- [ ] **Step 6: Commit**

```bash
git add deploy src/web/vite.config.ts .env.example docs/openapi.yaml README.md tests/e2e/ui-flow.cjs
git commit -m "docs: wire and document mapping integration"
```

### Task 10: Full Regression, Live Smoke Test, and Cleanup

**Files:**
- Modify only files required to fix regressions found by verification.

**Interfaces:**
- Verifies the complete feature and all existing distributed-system guarantees.

- [ ] **Step 1: Run backend verification**

Run: `./mvnw -B clean verify`

Expected: all module unit/integration tests PASS with no skipped Testcontainers tests.

- [ ] **Step 2: Run frontend verification**

Run: `cd src/web && npm ci && npm test && npm run build`

Expected: all tests PASS and production bundle builds.

- [ ] **Step 3: Run security and secret checks**

Run: `D:/Git/bin/bash.exe scripts/scan-tracked-secrets.sh`

Expected: PASS; no provider key is required or committed.

- [ ] **Step 4: Rebuild the local stack and verify health**

Run:

```bash
docker compose --env-file .env -f deploy/compose.yaml up --build -d
docker compose --env-file .env -f deploy/compose.yaml ps
```

Expected: PostgreSQL, RabbitMQ, five backend services, and Nginx are healthy.

- [ ] **Step 5: Run live map smoke without making CI depend on public services**

In the browser at `http://localhost:8080`, sign in as `passenger-1@demo.local`, search two Vietnam addresses with explicit buttons, select results, quote, verify route/duration/fare, and create a trip. Sign in as `driver-1@demo.local` and verify the same route snapshot appears before accepting.

- [ ] **Step 6: Run existing distributed-system smoke/regressions**

Run the existing seed and smoke scripts after adapting their create flow to obtain location and quote tokens through stubbed/demo addresses:

```bash
D:/Git/bin/bash.exe scripts/seed-demo.sh
D:/Git/bin/bash.exe scripts/smoke-trip.sh
```

Expected: full trip completes and payment reaches `SUCCEEDED`.

- [ ] **Step 7: Review final diff and commit verification fixes**

Run: `git diff --check && git status --short`

Commit only intentional fixes after confirming the status contains no unrelated user files:

```bash
git add src deploy docs/openapi.yaml README.md .env.example scripts tests
git commit -m "test: verify address routing flow"
```

