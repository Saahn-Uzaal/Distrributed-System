-- T10 — reservations + processed_commands (contract api-contracts.md §4.1/§4.2; state-machines.md §3).
-- reservations: id do Trip cấp (ổn định khi retry); state theo state machine §3.
-- Cột thêm so với spec §7 (chốt T05/T10):
--   * terminal_reason — phân biệt lý do terminal (state-machines.md §3, decision #5).
--   * pickup/destination/distance_m/fare_vnd — reserve command mang sẵn thông tin chuyến để
--     GET /drivers/me/offer phục vụ từ dữ liệu local (contract decision #4; T17 đọc các cột này).
CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    driver_id UUID NOT NULL,
    attempt INT NOT NULL CHECK (attempt >= 1),
    state TEXT NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    terminal_reason TEXT CHECK (terminal_reason IS NULL OR terminal_reason IN ('CANCELLED', 'REJECTED', 'OFFER_EXPIRED', 'TRIP_COMPLETED')),
    expires_at TIMESTAMPTZ NOT NULL,
    last_command_version INT NOT NULL DEFAULT 0,
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lng DOUBLE PRECISION NOT NULL,
    destination_lat DOUBLE PRECISION NOT NULL,
    destination_lng DOUBLE PRECISION NOT NULL,
    distance_m BIGINT NOT NULL CHECK (distance_m >= 0),
    fare_vnd BIGINT NOT NULL CHECK (fare_vnd > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Invariant 2: một trip+attempt tối đa một reservation (lưới an toàn DB; logic CAS đã chặn trước).
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservations_trip_attempt ON reservations (trip_id, attempt);

-- processed_commands: dedup internal command theo contract §4.1.
-- stored_response = JSONB {"status": <int>, "body": {...}} — replay trả đúng status/body gốc.
CREATE TABLE IF NOT EXISTS processed_commands (
    command_id UUID PRIMARY KEY,
    request_hash TEXT NOT NULL,
    stored_response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
