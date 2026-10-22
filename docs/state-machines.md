# State Machines — DS03 Ride-Hailing

Trạng thái: **CHỐT** (2026-10-18, task T05; cập nhật 2026-10-21 đợt sửa FIX_LIST: N21 transition SEARCHING→SEARCHING, N22 thời điểm ghi `trips.driver_id`, N24 release mọi reservationId bị bỏ). Nguồn sự thật cho mọi transition guard trong code. Chốt thêm 2 cột schema so với spec §7: `reservations.terminal_reason` và `sagas.context` (+ `trips.payment_attempt`) — ghi rõ ở từng mục.

## 1. Trip state machine (trip-service)

States: `SEARCHING`, `DRIVER_OFFERED`, `CANCELLING` *(transient)*, `ACCEPTED`, `IN_PROGRESS`, `COMPLETED`*, `CANCELLED`*, `NO_DRIVER*` — dấu * = terminal. Terminal không bao giờ quay về state trước (invariant 5).

```
                 ┌──────────────┐  cancel (không có reservation)
                 │   SEARCHING  │──────────────────────────────────► CANCELLED ⊘
        create   │              │  cancel (có reservation pending)
            ────►│              │──────────────────────────────────► CANCELLING
                 └──────┬───────┘                                   │ release confirmed
                        │ DriverProposed + reserve OK               ▼
                        ▼                                 CANCELLED ⊘
                 ┌──────────────┐  reject / offer expire (attempt còn)
        ◄────────│DRIVER_OFFERED│──────────────────────────────────► SEARCHING
                 │              │  attempt hết hạn (3 lần) hoặc deadline 120s
                 │              ├──────────────────────────────────► NO_DRIVER ⊘
                 │              │  accept + confirm OK
                 │              ▼
                 │         ┌─────────┐   start      ┌─────────────┐  complete
                 └─────────│ ACCEPTED │────────────►│ IN_PROGRESS │──────────► COMPLETED ⊘
                           └─────────┘              └─────────────┘
```

### Bảng transition (chốt)

| Từ | Đến | Trigger / guard | Side effects chốt |
|---|---|---|---|
| – | `SEARCHING` | `POST /api/trips` (idempotent key OK) | Saga `WAITING_MATCH`; outbox `TripRequested` attempt 1; trip_event `TRIP_CREATED`; notification passenger |
| `SEARCHING` | `DRIVER_OFFERED` | Consume `DriverProposed` đúng attempt + reserve thành công (reservationId đã lưu trước khi gọi) | Saga `WAITING_DRIVER`; trip_event `DRIVER_OFFERED`; notifications cả hai bên |
| `DRIVER_OFFERED` | `SEARCHING` | Reject hoặc offer TTL hết hạn → release **xác nhận** (hoặc đọc được terminal), attempt < 3 | Saga `WAITING_MATCH` attempt+1, excludedDriverIds += driver; xóa `trips.driver_id`/`driver_user_id` về NULL (chốt N22); trip_event `OFFER_REJECTED`/`OFFER_EXPIRED` + `RESERVATION_RELEASED`; outbox `TripRequested` attempt mới. Phase chỉ chuyển sang `RESERVING_DRIVER` **sau khi** nhận `DriverProposed` đúng attempt (chốt F06); event attempt cũ bị bỏ qua theo events.md §4.4 |
| `SEARCHING` | `SEARCHING` | Consume `NoDriverFound` đúng attempt, hoặc `DriverProposed` mà hết danh sách candidate chưa giữ được chỗ — attempt < 3 và chưa quá deadline | Saga `WAITING_MATCH`: attempt+1, `next_retry_at = now + MATCHING_RETRY_DELAY_MS` (env, default 5000 ms); outbox `TripRequested` attempt mới; **excludedDriverIds giữ nguyên** với NoDriverFound (không có candidate bị loại); không ghi trip_event mới (state không đổi). Hết attempts hoặc quá deadline → `NO_DRIVER` theo row kế tiếp |
| `DRIVER_OFFERED` / `SEARCHING` | `NO_DRIVER` | Hết 3 attempts hoặc deadline 120s (worker Saga) — **chỉ khi không còn reservation hiệu lực/đang xử lý** | Nếu còn reservation pending/hoạt động: Saga phase `COMPENSATING`, intent `RELEASE_RESERVATION` reconcile trước, rồi mới chuyển `NO_DRIVER`; trip_event `RESERVATION_RELEASED` + `NO_DRIVER`; notification passenger; Saga ABORTED (chốt F06) |
| `SEARCHING` | `CANCELLED` | Cancel, không có reservation pending | Saga ABORTED; trip_event `TRIP_CANCELLED`. **Không publish event "hủy matching"** (không tồn tại trong catalog — chốt F31): event `DriverProposed`/`NoDriverFound` attempt cũ tới sau đó bị bỏ qua — ACK + log theo events.md §4.4 |
| `DRIVER_OFFERED` (hoặc đang RESERVING) | `CANCELLING` | Cancel khi có reservation pending/hoạt động | Saga `COMPENSATING`, intent `RELEASE_RESERVATION`; HTTP 202; trip_event `CANCELLING` |
| `CANCELLING` | `CANCELLED` | Release xác nhận / không còn reserve pending (reconcile) | Driver giải phóng nếu đang RESERVED; trip_event `TRIP_CANCELLED`; notification passenger; Saga ABORTED |
| `DRIVER_OFFERED` | `ACCEPTED` | Accept bởi offered driver đúng attempt + confirm thành công (driver BUSY) | Saga tiếp tục chờ finish; trip_event `TRIP_ACCEPTED`; notifications cả hai bên |
| `ACCEPTED` | `IN_PROGRESS` | Start bởi assigned driver | trip_event `TRIP_STARTED` |
| `IN_PROGRESS` | `COMPLETED` | Complete bởi assigned driver | Outbox `TripCompleted` (paymentAttempt = trips.payment_attempt, khởi tạo 1); Saga phase `AWAITING_FINISH`, intent `FINISH_ASSIGNMENT`; trip_event `TRIP_COMPLETED`; notification passenger |

Chốt bổ sung:
- **NO_DRIVER chỉ đạt sau khi cleanup xong** (F06): không có nhánh terminal `NO_DRIVER` bỏ quên reservation còn hiệu lực hoặc reserve chưa rõ kết quả. Reserve bị timeout HTTP phải reconcile bằng `reservationId` ổn định (`GET /internal/reservations/{id}`) — **không suy ra thất bại chỉ vì HTTP timeout**; lệnh reserve tới trễ cho reservation đã release nhận 409 `TOMBSTONED_RESERVATION`, không hồi sinh assignment đã hủy (invariant 6).
- **Phase khi matching lại sau reject/expire** (F06): đang chờ proposal mới → Saga ở `WAITING_MATCH` (khớp việc phát `TripRequested` attempt mới); chỉ chuyển `RESERVING_DRIVER` khi có candidate đúng attempt. Không dùng lại candidate cũ cho attempt mới — danh sách candidate thuộc về attempt của nó; nếu thiết kế thay đổi, phải ghi rõ nhánh này tại đây.
- **CANCELLING** là state transient mới (spec §6 liệt kê lifecycle không có nó — đây là quyết định thiết kế T05, mục đích UI "đang hủy" + reconcile trước khi terminal). Không ảnh hưởng invariant 5.
- Cancel sau khi accept thắng → 409 (MVP không hỗ trợ hủy chuyến đã ACCEPTED trở đi).
- `trips.payment_attempt` (int, default 0): tăng lên 1 khi phát TripCompleted lần đầu. MVP không có cơ chế cấp attempt mới sau FAILED — `FAILED` là terminal (xem §5); cột này giữ làm schema dự phòng.

## 2. Saga orchestrator (trip-service — orchestrator duy nhất)

Cột chốt thêm: `context JSONB` (lưu candidates, index candidate hiện tại, reservationId), `lease_owner` nullable (claim worker). Schema đầy đủ:
`sagas(id, trip_id unique, phase, desired_action, next_retry_at, retry_count, deadline_at, last_error, context jsonb default '{}', version int default 0, created_at, updated_at)`.

### Phase & intent

| Phase | Ý nghĩa | `desired_action` hợp lệ |
|---|---|---|
| `WAITING_MATCH` | Chờ DriverProposed/NoDriverFound; giám sát deadline 120s | `NONE` (worker chỉ kiểm tra deadline) |
| `RESERVING_DRIVER` | Đang thử reserve candidate theo thứ tự | `RESERVE_NEXT_CANDIDATE`, `GET_RESERVATION_STATUS` (sau timeout HTTP) |
| `WAITING_DRIVER` | Chờ accept/reject; giám sát offer TTL 30s | `RELEASE_RESERVATION` (khi expire/cancel), `NONE` |
| `CONFIRMING` | Intent ACCEPT đã thắng, đang confirm idempotent | `CONFIRM_ASSIGNMENT`, `GET_RESERVATION_STATUS` |
| `COMPENSATING` | Intent CANCEL/REJECT thắng, đang bù trừ | `RELEASE_RESERVATION`, `GET_RESERVATION_STATUS` |
| `AWAITING_FINISH` | Trip COMPLETED, chờ finish assignment idempotent | `FINISH_ASSIGNMENT` |
| `COMPLETED` ⊘ | Terminal — driver đã release sau chuyến | – |
| `ABORTED` ⊘ | Terminal — trip CANCELLED/NO_DRIVER, không còn việc | – |

Quy tắc worker (chốt):
- Worker quét saga chưa terminal có `next_retry_at` ≤ now; **claim bằng transaction ngắn**: CAS `version` + ghi `lease_owner`; hai worker/request handler không chạy trùng intent.
- Không giữ DB lock khi gọi HTTP: lưu intent trước, gọi internal API, sau đó reconcile theo kết quả/timeout (GET reservation status).
- Tranh chấp accept vs cancel: transaction khóa/version-check saga quyết định **một** intent thắng; intent thua trả 409 cho client; worker thực thi intent đã lưu.
- **Mọi `reservationId` đã cấp mà trip bỏ phải được release** (chốt N24): cancel, reject hết candidate, NO_DRIVER hoặc reconcile timeout — Trip gửi `release` (tạo tombstone nếu release tới trước) và chỉ coi cleanup xong khi có xác nhận terminal; không tái dùng `commandId` cho thao tác khác; command lỗi (4xx/5xx/timeout) **không được lưu** để replay.

## 3. Reservation state machine (driver-service)

States: `RESERVED`, `CONFIRMED`, `RELEASED` ⊘, `EXPIRED` ⊘. Cột chốt thêm: `terminal_reason` ∈ {`CANCELLED`,`REJECTED`,`OFFER_EXPIRED`,`TRIP_COMPLETED`} (nullable đến khi terminal).

```
                    confirm (trip/attempt khớp)
        ┌──────────┐  ──────────────────────►  ┌────────────┐
        │ RESERVED │                            │ CONFIRMED  │
        └──────────┘                            └─────┬──────┘
             │  ▲                                      │ finish (trip COMPLETED)
   reserve   │  │ (không có transition ngược)          ▼
             │  └────────────────────────────────► RELEASED ⊘ (TRIP_COMPLETED)
             │ release (CANCELLED/REJECTED/OFFER_EXPIRED)
             ├───────────────────────────────────► RELEASED ⊘
             │ expire TTL — CAS chỉ khi state còn RESERVED
             └───────────────────────────────────► EXPIRED ⊘
```

Quy tắc chốt:
- **Reserve nguyên tử:** cùng transaction tạo reservation + CAS driver `AVAILABLE→RESERVED` (heartbeat mới); affected rows ≠ 1 → rollback. Không SELECT-rồi-UPDATE-muộn.
- **TTL expire** chỉ áp dụng cho state `RESERVED`, thực thi bằng compare-and-set (driver tự expire tại DB hoặc Saga release khi phát hiện hết hạn). **Không bao giờ expire `CONFIRMED`** — offer TTL hết không làm driver mất assignment đã confirm (invariant 8).
- **Tombstone:** release tới trước reserve cho cùng reservationId → chèn reservation ở terminal `RELEASED`; reserve trễ nhận 409 `TOMBSTONED_RESERVATION`, không tạo lại assignment đã hủy (invariant 6).
- Release/finish giải phóng driver **chỉ khi** `current_reservation_id`/`current_trip_id` vẫn khớp (fencing) — release cũ không đụng assignment mới.

## 4. Driver state machine (driver-service)

States: `OFFLINE`, `AVAILABLE`, `RESERVED`, `BUSY`.

| Từ | Đến | Trigger / guard |
|---|---|---|
| `OFFLINE` | `AVAILABLE` | PUT availability (luôn được phép từ OFFLINE) |
| `AVAILABLE` | `OFFLINE` | PUT availability — chỉ khi không có reservation hiệu lực, ngược lại 409 |
| `AVAILABLE` | `RESERVED` | Reserve CAS thành công (`current_reservation_id` set) |
| `RESERVED` | `AVAILABLE` | Release/expire xác nhận (fencing khớp) |
| `RESERVED` | `BUSY` | Confirm thành công (`current_trip_id` set, `current_reservation_id` giữ đến finish) |
| `BUSY` | `AVAILABLE` | Finish sau trip COMPLETED hoặc release bù trừ CONFIRMED; khớp cả current_reservation_id và current_trip_id |

- Heartbeat stale (> 15s): driver **không được chọn** cho chuyến mới nhưng state không đổi và assignment đang hoạt động không bị xóa.
- Invariant 1: tối đa một reservation hiệu lực hoặc một chuyến đang phục vụ — enforce bằng `current_reservation_id`/`current_trip_id` + CAS, không bằng lock toàn cục.

## 5. Payment status (payment-service)

States: `NOT_REQUESTED`, `PENDING`, `SUCCEEDED` ⊘, `FAILED`.

```
NOT_REQUESTED ──consume TripCompleted (attempt n)──► PENDING ──┬──► SUCCEEDED ⊘
                                                                └──► FAILED ⊘
```

- **MVP: `FAILED` là terminal — không có cơ chế retry payment chủ động** (spec §15 bỏ tính năng retry payment chủ động cho solo scope). Lỗi kỹ thuật trong cùng attempt được retry in-process bởi payment-service theo spec §10; việc Trip cấp attempt mới sau FAILED nằm ngoài MVP.
- Cột `trips.payment_attempt` và unique `(trip_id, attempt)` trong `payments` giữ làm schema dự phòng: nếu mở rộng retry chủ động sau này, mỗi attempt là record mới, không sửa lịch sử attempt cũ.
- Không có hai payment attempts hoạt động đồng thời cho một trip.
- Trip state độc lập: COMPLETED giữ nguyên dù payment FAILED (invariant 7).

## 6. Invariants bắt buộc → cơ chế enforce

| # | Invariant | Cơ chế |
|---:|---|---|
| 1 | Driver ≤ 1 reservation hiệu lực/chuyến phục vụ | CAS trên `drivers.status` + `current_reservation_id`/`current_trip_id` (mục 3–4) |
| 2 | Trip ≤ 1 assignment **hiệu lực** (từ ACCEPTED) | Saga version-check + confirm chỉ từ RESERVED đúng trip/attempt. `trips.driver_id`/`driver_user_id` ghi khi `DRIVER_OFFERED` (để driver được offer xem được trip), xóa về NULL khi reject/expire — cùng null hoặc cùng có giá trị; assignment hiệu lực chỉ tồn tại từ ACCEPTED đến finish/release bù trừ |
| 3 | Không start nếu assignment chưa confirm cả hai phía | Guard state: start chỉ từ `ACCEPTED` (chỉ đạt sau confirm OK) |
| 4 | Retry không tạo trip/payment/assignment trùng | Idempotency-Key (HTTP), `commandId`+hash (internal), unique `(trip_id, attempt)` (payment), eventId dedup (inbox) |
| 5 | Terminal không quay lại | Guard state ở mọi transition; stale event bỏ qua có log |
| 6 | Cancellation cũ không giải phóng driver chuyến mới | Fencing `current_reservation_id`/`current_trip_id` + tombstone reservation |
| 7 | COMPLETED giữ nguyên dù payment FAILED | Payment status độc lập, không rollback trip |
| 8 | Driver đã accept không tự AVAILABLE khi offer TTL hết | TTL chỉ CAS trên state `RESERVED`, không đụng `CONFIRMED`/`BUSY` |
