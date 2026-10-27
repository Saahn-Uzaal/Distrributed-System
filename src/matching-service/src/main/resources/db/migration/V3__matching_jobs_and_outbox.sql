-- T15 — matching job thật (spec §20.4) + outbox producer matching-service (events.md §4.6).
-- matching_jobs: một row mỗi (trip_id, attempt) — "lưu job/attempt". Row chỉ được commit trong
-- transaction cùng inbox dedup record và outbox PENDING (ACK sau commit, events.md §4.2);
-- rollback attempt fail → không có row → redelivery xử lý lại từ đầu.
-- state: PROPOSED (đã phát DriverProposed) | NO_DRIVER_FOUND (đã phát NoDriverFound).
CREATE TABLE IF NOT EXISTS matching_jobs (
    id BIGSERIAL PRIMARY KEY,
    trip_id UUID NOT NULL,
    attempt INT NOT NULL CHECK (attempt >= 1),
    state TEXT NOT NULL CHECK (state IN ('PROPOSED','NO_DRIVER_FOUND')),
    candidates JSONB,              -- danh sách có thứ tự khi PROPOSED: [{driverId, driverUserId, distanceM}]
    event_id UUID NOT NULL,        -- eventId của TripRequested tạo job này
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_matching_jobs_trip_attempt ON matching_jobs (trip_id, attempt);

-- Outbox producer matching-service (events.md §4.6): id = eventId ổn định khi publish lại.
-- Chỉ phát DriverProposed/NoDriverFound (§3.2/§3.3) — routing key matching.proposed/matching.unavailable
-- được trip.inbox bind theo `matching.#` (topology T04; queue declare bởi trip-service ở T16).
CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL CHECK (event_type IN ('DriverProposed','NoDriverFound')),
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,             -- eventId của TripRequested tương ứng (events.md §5)
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT')),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 0 = không biết version trip (matching không đọc Trip DB) — envelope bỏ field này (§2 optional).
    aggregate_version INT NOT NULL DEFAULT 0,
    schema_version INT NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    producer TEXT NOT NULL DEFAULT 'matching-service',
    routing_key TEXT NOT NULL CHECK (routing_key <> ''),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';
