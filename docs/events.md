# Event Contracts — RabbitMQ (DS03)

Trạng thái: **CHỐT** (2026-10-18, task T05; cập nhật topology 2026-10-19 đợt sửa F02 — thêm binding `payment.#` → `trip.inbox`; cập nhật 2026-10-21 đợt sửa FIX_LIST: N15 mandatory/returns, N21 hành vi NoDriverFound/hết candidate, N23 republish DLQ). Topology khớp 1:1 với topology đã declare và kiểm chứng ở T04 + F02 (`infra/rabbitmq/declare-topology.sh`). Chưa có consumer nghiệp vụ — các task T13/T14+ implement đúng theo đây.

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
- Message **persistent** (`delivery_mode=2`); publisher dùng **`mandatory=true` + confirms (ReturnsCallback)**; outbox event có `eventId` ổn định khi publish lại.
- **Message bị broker trả về (returned — không có binding/routing key khớp):** outbox **giữ PENDING**, ghi `last_error`, **không** đánh dấu SENT; worker retry ở vòng quét kế tiếp. Không bao giờ coi returned là đã gửi.
- Delivery semantics: **at-least-once + dedup phía consumer** (inbox unique `(consumer_name, event_id)`; ACK sau khi transaction nghiệp vụ + inbox record commit cùng transaction).
- Mỗi service nhận event qua **queue riêng** — không chia sẻ queue giữa hai consumer khác nghiệp vụ.
- **Topology được declare bởi service khi khởi động** (Spring AMQP declarables: exchange/queue/binding đúng như bảng trên); `infra/rabbitmq/declare-topology.sh` chỉ là công cụ phụ cho debug và reset volume broker.

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

**Validation khi consume:** thiếu field bắt buộc / sai kiểu (`malformed_envelope`) hoặc `eventType` consumer không xử lý (`unsupported_event_type`) → **republish** message sang `ride.events.dlx` với routing key = tên DLQ của queue đó (`matching.dlq`/`trip.dlq`/`payment.dlq`) kèm header `x-dlq-reason: <lý do>`, rồi **ACK message gốc**. Không bao giờ retry event malformed như lỗi mạng. (Không dùng `reject(requeue=false)` cho trường hợp này — broker dead-letter chỉ thêm được `x-death`, không thêm được `x-dlq-reason`.)

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
(Note: không có event "hủy matching" trong catalog — khi trip đã terminal, event attempt cũ bị bỏ qua theo §4.4.)
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
- Candidate chỉ mang `driverId`/`driverUserId`/`distanceM` — **không có tên/số xe** (chốt N27): MVP không hiển thị thông tin driver cho passenger; trip-service không đọc Driver DB.
- **Chốt sửa R02 (2026-10-20):** candidate mang cả driverId (profile) và driverUserId (JWT sub). Matching T15 giữ nguyên ánh xạ từ candidates API; Trip T16 lưu driver_id + driver_user_id cùng assignment. Không suy luận hai UUID bằng nhau, không đọc chéo DB.
- Trip thử reserve lần lượt từ đầu danh sách; candidate conflict → thử tiếp.
- **Hết danh sách mà chưa giữ được chỗ, hoặc nhận `NoDriverFound` đúng attempt** — nếu attempt < `MAX_MATCHING_ATTEMPTS` và chưa quá deadline: transition `SEARCHING → SEARCHING` (state-machines.md §1): attempt+1, `next_retry_at = now + MATCHING_RETRY_DELAY_MS`, phát `TripRequested` attempt mới qua outbox. **Excluded list giữ nguyên** với NoDriverFound (không có candidate nào bị loại nên không thêm vào excluded). Hết attempts hoặc quá deadline → `NO_DRIVER`.

### 3.3. NoDriverFound — `matching.unavailable` — matching-service → trip-service
Payload:
```json
{"tripId": "uuid", "attempt": 1, "reason": "NO_CANDIDATES"}
```
- `reason` enum chốt: `NO_CANDIDATES` (không có driver AVAILABLE + heartbeat mới trong bán kính). Matching chỉ phát event này.
- Hành vi phía Trip khi nhận NoDriverFound đúng attempt: nếu còn lượt attempt và chưa quá deadline → retry theo §3.2 (`SEARCHING → SEARCHING`, attempt+1, `MATCHING_RETRY_DELAY_MS`); hết attempts hoặc quá deadline → state `NO_DRIVER` do **Trip** quyết định (spec §10), không phải matching.

### 3.4. TripCompleted — `trip.completed` — trip-service → payment-service
Payload:
```json
{"tripId": "uuid", "attempt": 1, "amountVnd": 32000, "currency": "VND"}
```
- `currency` luôn `"VND"` (MVP). Payment chỉ xử lý đúng trip/attempt; unique `(trip_id, attempt)` chống thu trùng.
- Field `attempt` thống nhất với §3.5/§3.6 (chốt N26); cột DB `trips.payment_attempt` là schema, không phải tên field event.

### 3.5. PaymentSucceeded — `payment.succeeded` — payment-service → trip-service
Payload:
```json
{"tripId": "uuid", "paymentId": "uuid", "attempt": 1, "amountVnd": 32000}
```

### 3.6. PaymentFailed — `payment.failed` — payment-service → trip-service
Payload:
```json
{"tripId": "uuid", "paymentId": "uuid", "attempt": 1, "reason": "SIMULATED_DECLINE"}
```
- `reason`: mã kỹ thuật/giả lập (dev config quyết định tỷ lệ fail) — thống nhất tên field với §3.3/§3.5 (chốt N26); cột DB `payments.failure_code` là schema, không phải tên field event.
- Trip giữ state `COMPLETED`, chỉ cập nhật `payment_status=FAILED` — không rollback chuyến đã chạy.

## 4. Quy tắc xử lý phía consumer (chốt)

1. **Dedup:** trước khi xử lý, insert `(consumer_name, event_id)` vào inbox cùng transaction với thay đổi nghiệp vụ; duplicate → ACK an toàn, không thực thi lại.
2. **ACK sau commit:** chỉ ACK khi cả transaction nghiệp vụ + inbox record đã commit. Rollback → không ACK → redelivery.
3. **Retry transient** (DB lock timeout, lỗi mạng nội bộ...): retry in-process tối đa 3 lần, backoff `1s / 3s / 10s` (env); vẫn fail → `reject(requeue=false)` → DLQ (message vào DLQ theo cách này chỉ mang `x-death`, **không** có `x-dlq-reason`). Không nack-requeue vô hạn.
4. **Stale event** (attempt/version cũ hơn state hiện tại — vd DriverProposed attempt 1 tới khi trip đã ở attempt 2): **log + bỏ qua, ACK** (không vào DLQ — event hợp lệ nhưng cũ). Event chưa đủ điều kiện xử lý → lưu pending/reconcile qua Saga, không mất im lặng.
5. **Không dựa vào thứ tự giao của RabbitMQ:** mọi quyết định dựa trên `attempt`/`aggregateVersion`/state + version check.
6. **Outbox (producer):** ghi nghiệp vụ + event cùng DB transaction; worker publish với `mandatory=true` + confirms/ReturnsCallback, chờ confirm rồi đánh dấu `SENT`; message bị returned → giữ PENDING + ghi `last_error`, không đánh dấu SENT (§1); crash sau confirm có thể gửi trùng — consumer phải chịu được (dedup §4.1).

## 5. Correlation & causation

- `correlationId`: lấy từ request gốc khởi tạo luồng (X-Correlation-ID); event hồi phục/replay giữ correlation gốc.
- `causationId`: eventId của event trực tiếp gây ra event mới (vd TripCompleted phát sau khi consume... không — TripCompleted do HTTP complete trigger nên causationId = null; DriverProposed có causationId = eventId của TripRequested tương ứng).

## 6. DLQ và replay

- Hai đường vào DLQ: (a) **republish chủ động** cho event malformed/unsupported (§2) — message mang `x-death` **và** `x-dlq-reason`; (b) `reject(requeue=false)` sau khi hết retry transient (§4.3) — message chỉ mang `x-death`.
- `scripts/replay-dlq.sh` (phạm vi T26/T31): đọc DLQ qua management API, hiển thị từng event, replay **có chủ đích** sau khi sửa lỗi — giữ nguyên `eventId`, không sửa payload của event cũ để giả thành công.
