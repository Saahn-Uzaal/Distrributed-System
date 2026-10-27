-- T23 — payment consumer (spec §20.5) + outbox producer payment-service (events.md §4.6).
-- payments: một row mỗi (trip_id, attempt) — unique(trip_id, attempt) bảo vệ việc thu tiền giả lập
-- (spec §9/§7): duplicate TripCompleted không tạo payment thứ hai cho cùng trip/attempt.
-- Row chỉ được commit trong transaction cùng inbox dedup record và outbox PENDING
-- (ACK sau commit, events.md §4.2); rollback attempt fail → không có row → redelivery xử lý lại.
-- Chốt T23: status ghi TRỰC TIẾP SUCCEEDED/FAILED (kết quả giả lập quyết định trong transaction) —
-- KHÔNG có trạng thái PENDING trung gian trên payments; trips.payment_status giữ 'PENDING' cho tới
-- khi trip-service nhận PaymentSucceeded/PaymentFailed ở T24.
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    attempt INT NOT NULL CHECK (attempt >= 1),
    amount_vnd BIGINT NOT NULL CHECK (amount_vnd > 0),
    currency TEXT NOT NULL DEFAULT 'VND',   -- MVP: luôn 'VND' (events.md §3.4)
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
    failure_code TEXT,                       -- mã kỹ thuật/giả lập khi FAILED (vd SIMULATED_DECLINE)
    event_id UUID NOT NULL,                  -- eventId của TripCompleted tạo payment này
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_trip_attempt ON payments (trip_id, attempt);

-- Outbox producer payment-service (events.md §4.6): id = eventId ổn định khi publish lại.
-- Chỉ phát PaymentSucceeded/PaymentFailed (§3.5/§3.6) — routing key payment.succeeded/payment.failed
-- được trip.inbox bind theo `payment.#` (topology T04/F02; queue declare bởi trip-service ở T16).
CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL CHECK (event_type IN ('PaymentSucceeded','PaymentFailed')),
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,             -- eventId của TripCompleted tương ứng (events.md §5)
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT')),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 0 = không biết version trip (payment không đọc Trip DB) — envelope bỏ field này (§2 optional).
    aggregate_version INT NOT NULL DEFAULT 0,
    schema_version INT NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    producer TEXT NOT NULL DEFAULT 'payment-service',
    routing_key TEXT NOT NULL CHECK (routing_key <> ''),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';
