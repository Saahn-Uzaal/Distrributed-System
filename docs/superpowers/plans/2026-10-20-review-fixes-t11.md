# Review fixes and T11 Implementation Plan

> Execute with subagent-driven-development; independent Trip repairs may run alongside Driver work. The root agent owns shared contracts, integration and final verification.

**Goal:** Resolve R01–R07 in `docs/T09-T12_REVIEW.md`, implement T11, and verify durable reservation transitions.

**Architecture:** Keep each service's JDBC/Flyway persistence. Scope Trip idempotency by authenticated passenger; carry distinct driver/profile and user IDs through contracts. Claim internal commands atomically and serialize per reservation, retaining driver CAS and fencing. Expiry is a DB-backed scheduled worker for RESERVED only.

**Tech Stack:** Existing Java 21, Spring Boot 4.1.1, PostgreSQL 17, Testcontainers 1.21.4, Jackson 3.

**Spec:** `docs/api-contracts.md`, `docs/state-machines.md`, `docs/T09-T12_REVIEW.md`, supplied DS03 specification §6/§8/§20.4 and course PDF §7.

## Global Constraints

- No cross-service database access; no implementation of T13 or T15–T25.
- Existing applied migrations remain immutable; new migrations preserve valid existing rows and replay data.
- Commands bind operation + reservationId + body except commandId/commandVersion; versions remain INT with explicit 1..2147483647 validation.
- User request authorizes both outstanding review repairs and T11; the document's default one-task rule does not limit that explicit request.
- This directory has no Git metadata; work in the authorized workspace, keep an external backup, do not initialize Git or create fabricated commits.

## Task 1: Trip repairs (R01, R02, R07)

Files: TripService, TripRepository, IdempotencyKeyRepository, new Trip migration V3, TripIntegrationTest.

Interfaces: idempotency claim/find accept principalId; persisted `driver_user_id` identifies the assigned driver's JWT sub, while `driver_id` remains the profile ID. Driver candidates and DriverProposed carry `driverUserId` in the shared contract. Pagination offset is long.

- [ ] Add tests for two passengers reusing a key, assignment with unequal profile/user IDs, exclusion of other users and large pages.
- [ ] Run new tests and observe failures against current implementation.
- [ ] Add migration: backfill idempotency principal from stored trip, use principal/operation/key PK; add driver_user_id/index with assignment consistency guard.
- [ ] Implement scoped replay, authorization using driver_user_id and long offsets.
- [ ] Verify Trip module, including concurrent create replay, and self-review.

## Task 2: Driver repairs (R03, R04, R05, R06)

Files: DriverService, DriverRepository, ReservationService, ReservationRepository, new migration V4, Driver regression tests.

Interfaces: command claim stores a transaction-local placeholder then completed response; per-reservation transaction lock handles absent rows; per-user lock serializes profile creation. Candidates add driverUserId. Hash becomes SHA256(operation + '|' + reservationId + '|' + legacy body hash).

- [ ] Make review probes permanent with concurrency synchronization that remains valid after serialization; observe expected failures.
- [ ] Implement principal lock, command claim, resource-bound hash, version bounds and candidate mapping.
- [ ] Migrate existing RESERVE command hashes using the same wrapper and stored response reservationId.
- [ ] Verify Driver module; preserve legacy successful replay after schema upgrade.

## Task 3: T11 lifecycle

Files: lifecycle DTOs/controller/service, reservation repository, migration V4, scheduled expiry worker/config and ReservationLifecycleIntegrationTest.

- [ ] Test missing endpoints: release-before-reserve; confirm guard; finish; stale versions; wrong IDs; concurrent transitions; delayed old release/finish; replay and different-operation conflict; expiry excludes CONFIRMED.
- [ ] Observe missing-handler failures before implementation.
- [ ] Relax reservation business columns only for genuine release-before-reserve tombstones; add live-reservation completeness constraints/index.
- [ ] Implement confirm (RESERVED + unexpired + matching trip/driver/attempt), release (including compensation of CONFIRMED) and finish (CONFIRMED + matching trip).
- [ ] Update reservation + fenced driver pointers in one transaction. Terminal rows never revive; successful terminal no-ops advance accepted command version.
- [ ] Implement bounded scan and transactional CAS expiry, retaining last command version; restart does not depend on RAM.
- [ ] Verify same-command overlap, release-before-reserve overlap and confirm/expiry competition using real Postgres/HTTP.

## Task 4: Integration, review and evidence

- [ ] Review migrations against a V2/V3 database with existing replay records.
- [ ] Run full backend clean verify and frontend existing checks if API changes affect it.
- [ ] Independent code review; resolve findings and run relevant checks for changes.
- [ ] Update task T11 log, fix log, review status, backlog/progress/handoff and README with actual evidence.
- [ ] Deliver outcome and limits; do not proceed to T13.
