# Event Contracts — RabbitMQ (DS03)

Trạng thái: **CHỐT** (2026-10-18, task T05; cập nhật topology 2026-10-19 đợt sửa F02 — thêm binding `payment.#` → `trip.inbox`). Topology khớp 1:1 với topology đã declare và kiểm chứng ở T04 + F02 (`infra/rabbitmq/declare-topology.sh`). Chưa có consumer nghiệp vụ — các task T13/T14+ implement đúng theo đây.

## 1. Transport & topology (khớp T04)

| Object | Loại / args | Binding vào | Routing key |
|---|---|---|---|
| `ride.events` | topic, durable | – | – |
| `ride.events.dlx` | topic, durable | – | – |
| `matching.inbox` | queue, durable, DLX→`ride.events.dlx` (rk=`matching.dlq`) | `ride.events` | `trip.requested` |
| `trip.inbox` | queue, durable, DLX→`ride.events.dlx` (rk=`trip.dlq`) | `ride.events` | `matching.#`, `payment.#` (F02: PaymentSucceeded/PaymentFailed §3.5/§3.6) |
| `payment.inbox` | queue, durable, DLX→`ride.events.dlx` (rk=`payment.dlq`) | `ride.events` | `trip.completed` |
| `matching.dlq`, `trip.dlq`, `payment.dlq` | queue, durable | `ride.events.dlx` | = tên queue |

Quy tắc transport chốt:
- Message **persistent** (`delivery_mode=2`), publisher dùng **confirms**; outbox event có `eventId` ổn định khi publish lại.
- Delivery semantics: **at-least-once + dedup phía consumer** (inbox unique `(consumer_name, event_id)`; ACK sau khi transaction nghiệp vụ + inbox record commit cùng transaction).
- Mỗi service nhận event qua **queue riêng** — không chia sẻ queue giữa hai consumer khác nghiệp vụ.

## 2. Envelope chuẩn (schemaVersion = 1)

```json
{
  "eventId": "uuid",
  "eventType": "TripRequested",
  "schemaVersion": 1,
  "occurredAt": "2026-10-17T00:00:00Z",
  "producer": "trip-service",
  "correlationId": "uuid",
  "causationId": "uuid",
  "aggregateId": "trip-uuid",
  "aggregateVersion": 1,
  "payload": { }
}
```

| Field | Bắt buộc | Quy tắc |
|---|---|---|
| `eventId` | ✔ | UUID; ổn định qua mọi lần redelivery/replay (không sinh lại) |
| `eventType` | ✔ | Một trong 6 loại §3 |
| `schemaVersion` | ✔ | Integer; consumer chỉ chấp nhận `1`; lớn hơn → DLQ `unsupported_schema_version` |
| `occurredAt` | ✔ | ISO-8601 UTC tại producer |
| `producer` | ✔ | Tên service phát (vd `trip-service`) |
| `correlationId` | ✔ | UUID; giữ nguyên xuyên luồng nghiệp vụ (api-contracts §5) |
| `causationId` | ✖ | eventId của event đã gây ra event này (nếu có) |
| `aggregateId` | ✔ | ID aggregate gốc (tripId cho mọi event hiện tại) |
| `aggregateVersion` | ✖ | Version aggregate tại thời điểm phát; consumer dùng để phát hiện stale |
| `payload` | ✔ | Theo schema từng eventType §3 |

**Validation khi consume:** thiếu field bắt buộc / sai kiểu → reject không requeue vào DLQ, header `x-dlq-reason: malformed_envelope`. `eventType` consumer không xử lý (sai binding) → DLQ `unsupported_event_type`. Không bao giờ retry event malformed như lỗi mạng.

## 3. Catalog events (đủ 6/6 theo spec §9)

### 3.1. TripRequested — `trip.requested` — trip-service → matching-service
Payload:
```json
{
  "tripId": "uuid",
  "attempt": 1,
  "pickup": {"latitude": 10.7769, "longitude": 106.7009},
  "excludedDriverIds": []
}
```
- `attempt` bắt đầu từ 1, tăng khi matching lại sau reject/expire.
- `excludedDriverIds`: driver đã từ chối/hết hạn trong các attempt trước — matching không chọn lại.

### 3.2. DriverProposed — `matching.proposed` — matching-service → trip-service
Payload (chốt: **danh sách có thứ tự**, không phải một driverId):
```json
{
  "tripId": "uuid",
  "attempt": 1,
  "candidates": [
    {"driverId": "uuid-a", "driverUserId": "user-uuid-a", "distanceM": 412},
    {"driverId": "uuid-b", "driverUserId": "user-uuid-b", "distanceM": 987}
  ]
}
```
- `candidates` sắp theo rank: `distanceM` tăng, tie-break `driverId`; max `MATCHING_MAX_CANDIDATES=5`.
- **Chốt sửa R02 (2026-10-20):** candidate mang cả driverId (profile) và driverUserId (JWT sub). Matching T15 giữ nguyên ánh xạ từ candidates API; Trip T16 lưu driver_id + driver_user_id cùng assignment. Không suy luận hai UUID bằng nhau, không đọc chéo DB.
- Trip thử reserve lần lượt từ đầu danh sách; candidate conflict → thử tiếp; hết danh sách mà chưa giữ được chỗ → chờ attempt kế tiếp (do Saga điều khiển).

### 3.3. NoDriverFound — `matching.unavailable` — matching-service → trip-service
Payload:
```json
{"tripId": "uuid", "attempt": 1, "reason": "NO_CANDIDATES"}
```
- `reason` enum chốt: `NO_CANDIDATES` (không có driver AVAILABLE + heartbeat mới trong bán kính). Matching chỉ phát event này; việc hết số lần attempt → state `NO_DRIVER` do **Trip** quyết định (spec §10), không phải matching.

### 3.4. TripCompleted — `trip.completed` — trip-service → payment-service
Payload:
```json
{"tripId": "uuid", "paymentAttempt": 1, "amountVnd": 32000, "currency": "VND"}
```
- `currency` luôn `"VND"` (MVP). Payment chỉ xử lý đúng trip/attempt; unique `(trip_id, attempt)` chống thu trùng.

### 3.5. PaymentSucceeded — `payment.succeeded` — payment-service → trip-service
Payload:
```json
{"tripId": "uuid", "paymentId": "uuid", "attempt": 1, "amountVnd": 32000}
```

### 3.6. PaymentFailed — `payment.failed` — payment-service → trip-service
Payload:
```json
{"tripId": "uuid", "paymentId": "uuid", "attempt": 1, "failureCode": "SIMULATED_DECLINE"}
```
- `failureCode`: mã kỹ thuật/giả lập (dev config quyết định tỷ lệ fail). Trip giữ state `COMPLETED`, chỉ cập nhật `payment_status=FAILED` — không rollback chuyến đã chạy.

## 4. Quy tắc xử lý phía consumer (chốt)

1. **Dedup:** trước khi xử lý, insert `(consumer_name, event_id)` vào inbox cùng transaction với thay đổi nghiệp vụ; duplicate → ACK an toàn, không thực thi lại.
2. **ACK sau commit:** chỉ ACK khi cả transaction nghiệp vụ + inbox record đã commit. Rollback → không ACK → redelivery.
3. **Retry transient** (DB lock timeout, lỗi mạng nội bộ...): retry in-process tối đa 3 lần, backoff `1s / 3s / 10s` (env); vẫn fail → reject không requeue → DLQ (`x-death` ghi số lần). Không nack-requeue vô hạn.
4. **Stale event** (attempt/version cũ hơn state hiện tại — vd DriverProposed attempt 1 tới khi trip đã ở attempt 2): **log + bỏ qua, ACK** (không vào DLQ — event hợp lệ nhưng cũ). Event chưa đủ điều kiện xử lý → lưu pending/reconcile qua Saga, không mất im lặng.
5. **Không dựa vào thứ tự giao của RabbitMQ:** mọi quyết định dựa trên `attempt`/`aggregateVersion`/state + version check.
6. **Outbox (producer):** ghi nghiệp vụ + event cùng DB transaction; worker publish với confirms, chờ confirm rồi đánh dấu `sent`; crash sau confirm có thể gửi trùng — consumer phải chịu được (dedup §4.1).

## 5. Correlation & causation

- `correlationId`: lấy từ request gốc khởi tạo luồng (X-Correlation-ID); event hồi phục/replay giữ correlation gốc.
- `causationId`: eventId của event trực tiếp gây ra event mới (vd TripCompleted phát sau khi consume... không — TripCompleted do HTTP complete trigger nên causationId = null; DriverProposed có causationId = eventId của TripRequested tương ứng).

## 6. DLQ và replay

- Message vào DLQ mang header `x-death` (reason, exchange, queue, count) + `x-dlq-reason` khi do validation.
- `scripts/replay-dlq.sh` (phạm vi T26/T31): đọc DLQ qua management API, hiển thị từng event, replay **có chủ đích** sau khi sửa lỗi — giữ nguyên `eventId`, không sửa payload của event cũ để giả thành công.
