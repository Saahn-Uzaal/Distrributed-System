-- T14 — consumer inbox (events.md §4): dedup record + probe handler table.
-- inbox_events: unique (consumer_name, event_id) — at-least-once delivery an toàn; row chỉ được
-- commit trong transaction cùng thay đổi nghiệp vụ (ACK sau commit, §4.2). Rollback attempt
-- thất bại không để lại row → redelivery/retry xử lý lại từ đầu.

CREATE TABLE IF NOT EXISTS inbox_events (
    id BIGSERIAL PRIMARY KEY,
    consumer_name TEXT NOT NULL,
    event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    correlation_id UUID,
    aggregate_id UUID,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_inbox_consumer_event ON inbox_events (consumer_name, event_id);

-- T14 probe: side effect quan sát được của handler execution (spec §20.4 "dùng handler probe test
-- trước khi có matching thật"). Handler phải idempotent theo eventId — insert ON CONFLICT DO NOTHING.
-- Thay bằng logic matching thật ở T15 (matching_jobs/matching_attempts); bảng này chỉ phục vụ
-- chứng minh "handler chạy đúng một lần" cho acceptance T14.
CREATE TABLE IF NOT EXISTS matching_probe_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
