package com.ridehailing.matching.inbox;

import com.ridehailing.matching.event.EventEnvelope;

/**
 * T14 — handler nghiệp vụ cho event inbox (probe tới khi có matching thật ở T15).
 * Handler phải idempotent theo eventId: event có thể được redelivery sau rollback,
 * thực thi lại không được tạo side effect trùng (events.md §4.1).
 */
public interface InboxEventHandler {

    /** Xử lý event đã validate; chạy trong transaction cùng dedup record (§4.2). */
    void handle(EventEnvelope envelope);
}
