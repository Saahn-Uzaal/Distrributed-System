package com.ridehailing.payment.inbox;

import com.ridehailing.payment.event.EventEnvelope;

/**
 * T23 — handler nghiệp vụ cho event inbox (TripCompleted).
 * Handler phải idempotent theo eventId: event có thể được redelivery sau rollback,
 * thực thi lại không được tạo side effect trùng (events.md §4.1) — unique(trip_id, attempt)
 * trên payments là lớp bảo vệ cuối cùng (spec §9).
 */
public interface InboxEventHandler {

    /** Xử lý event đã validate; chạy trong transaction cùng dedup record (§4.2). */
    void handle(EventEnvelope envelope);
}
