-- T16 — Saga orchestrator + consumer inbox (state-machines.md §2, events.md §4).
-- sagas: một row per trip; phase/desired_action theo bảng §2. CAS version + phase guard
-- serialize các intent của cùng trip (worker rule §2: "transaction khóa/version-check saga").
CREATE TABLE IF NOT EXISTS sagas (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL UNIQUE,
    phase TEXT NOT NULL DEFAULT 'WAITING_MATCH' CHECK (phase IN ('WAITING_MATCH','RESERVING_DRIVER',
        'WAITING_DRIVER','CONFIRMING','COMPENSATING','AWAITING_FINISH','COMPLETED','ABORTED')),
    desired_action TEXT NOT NULL DEFAULT 'NONE' CHECK (desired_action IN ('NONE','RESERVE_NEXT_CANDIDATE',
        'GET_RESERVATION_STATUS','RELEASE_RESERVATION','CONFIRM_ASSIGNMENT','FINISH_ASSIGNMENT')),
    next_retry_at TIMESTAMPTZ,
    retry_count INT NOT NULL DEFAULT 0,
    deadline_at TIMESTAMPTZ,
    last_error TEXT,
    context JSONB NOT NULL DEFAULT '{}',
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- inbox_events: dedup record consumer trip-service (events.md §4.1): unique
-- (consumer_name, event_id). Row chỉ commit trong transaction cùng thay đổi nghiệp vụ
-- (§4.2); rollback attempt thất bại không để lại row → redelivery xử lý lại từ đầu.
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
