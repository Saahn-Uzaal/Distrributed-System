package com.ridehailing.trip.inbox;

import com.ridehailing.trip.event.EventEnvelope;

/**
 * T16 — handler nghiệp vụ cho event inbox (DriverProposed/NoDriverFound).
 * Handler phải idempotent theo eventId: event có thể được redelivery sau rollback,
 * thực thi lại không được tạo side effect trùng (events.md §4.1) — cơ chế: reservationId/
 * commandId deterministic + reconcile GET trên cùng reservationId (contract §4.1/§4.2).
 */
public interface InboxEventHandler {

    /** Xử lý event đã validate; chạy trong transaction cùng dedup record (§4.2). */
    void handle(EventEnvelope envelope);
}
