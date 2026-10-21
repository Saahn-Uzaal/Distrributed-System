-- T12-2 (N33): field envelope + retry cho outbox (events.md §4.6).
ALTER TABLE outbox ADD COLUMN aggregate_version INT NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN schema_version TEXT NOT NULL DEFAULT 'v1';
ALTER TABLE outbox ADD COLUMN producer TEXT NOT NULL DEFAULT 'trip-service';
ALTER TABLE outbox ADD COLUMN routing_key TEXT NOT NULL DEFAULT '';
ALTER TABLE outbox ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE outbox ADD COLUMN last_error TEXT;

-- T12-3 (N32 trip): trips.reservation_id + unique chống ghi trùng lịch sử khi retry.
-- trip_events.id GIỮ BIGSERIAL — lệch đặc tả có chủ đích: đây là id nội bộ của history,
-- không phải event identity lộ ra envelope (outbox.id UUID ổn định theo events.md §4.6).

ALTER TABLE trips ADD COLUMN reservation_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_trip_events_trip_type_version ON trip_events (trip_id, type, version);
