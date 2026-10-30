# API Contracts — DS03 Ride-Hailing Microservices

Trạng thái: **CHỐT** (2026-10-18, task T05; cập nhật 2026-10-21 đợt sửa FIX_LIST: F31 bỏ "event hủy matching", N22 thời điểm ghi `trips.driver_id`, N24 release mọi reservationId bị bỏ, thêm mã lỗi 405/415). Đây là nguồn sự thật duy nhất cho contract REST. Mọi thay đổi sau này phải đi qua một task riêng và cập nhật file này trước khi code.

Nguồn yêu cầu: spec DS03 mục 8 (REST), mục 9 (event — xem `events.md`), mục 10–11 (saga, security). Đề môn học: COURSE PROJECT SPECIFICATION §7.

---

## 1. Quy ước chung

| Hạng mục | Quy định chốt |
|---|---|
| Base URL public | Qua Nginx `http://localhost:8080/api/...` (Nginx proxy theo bảng §3) |
| Base URL internal | Trục tiếp service trong Docker network, chỉ mạng nội bộ: identity :8081, driver :8082, trip :8083, matching :8084, payment :8085 |
| Format | `application/json`, UTF-8. Request body tối đa **64 KiB** → vượt trả 413 |
| Auth public | Header `Authorization: Bearer <JWT>` (RS256 — xem §2) |
| Auth internal | Header `X-Internal-Token` = giá trị env `INTERNAL_API_TOKEN` (so sánh constant-time). Thiếu/sai → 401 `INVALID_INTERNAL_CREDENTIAL` |
| Correlation ID | Header `X-Correlation-ID` — quy tắc ở §5 |
| Idempotency | Header `Idempotency-Key` (bắt buộc cho `POST /api/trips`) |
| Timestamps | ISO-8601 UTC, ví dụ `2026-10-18T09:30:00Z` |
| Tiền | Integer VND (`BIGINT`), không float. Khoảng cách: integer meter |
| Tọa độ | Decimal degrees; latitude ∈ [-90, 90], longitude ∈ [-180, 180], phải finite |

### 1.1. Định dạng lỗi (mọi endpoint)

```json
{
  "code": "STATE_CONFLICT",
  "message": "Trip is already ACCEPTED; cancel is only allowed before accept.",
  "correlationId": "9f2c...uuid",
  "details": {"tripId": "...", "state": "ACCEPTED"}
}
```

Không bao giờ trả stack trace. `details` tùy chọn (object hoặc array).

### 1.2. Bảng mã lỗi chốt

| HTTP | code | Ý nghĩa / khi nào dùng |
|---:|---|---|
| 400 | `INVALID_INPUT` | Body sai schema, tọa độ ngoài khoảng, email/password/role không hợp lệ, thiếu `Idempotency-Key`, key sai định dạng UUID |
| 401 | `UNAUTHORIZED` | Thiếu token / token sai chữ ký / hết hạn / sai issuer-audience |
| 401 | `INVALID_INTERNAL_CREDENTIAL` | Internal API thiếu/sai `X-Internal-Token` |
| 403 | `FORBIDDEN` | Role không đủ cho thao tác (vd passenger gọi endpoint DRIVER) |
| 404 | `NOT_FOUND` | Resource không tồn tại **hoặc** caller không có quyền xem — chính sách nhất quán: không lộ sự tồn tại resource của người khác |
| 409 | `IDEMPOTENCY_MISMATCH` | Cùng `Idempotency-Key`, payload khác |
| 409 | `ACTIVE_TRIP_EXISTS` | Passenger đã có trip chưa terminal (`state` ∉ {COMPLETED, CANCELLED, NO_DRIVER}) — tối đa một chuyến hoạt động per passenger (chốt T12-6/N28) |
| 409 | `STATE_CONFLICT` | Chuyển trạng thái sai (details chứa state hiện tại) |
| 409 | `NOT_OFFERED_DRIVER` | Accept/reject bởi driver không phải người được offer cho trip+attempt đó |
| 409 | `NOT_ASSIGNED_DRIVER` | Start/complete bởi driver không phải driver đã assign |
| 409 | `EMAIL_TAKEN` | Register email đã tồn tại |
| 409 | `PLATE_TAKEN` | Tạo profile driver với plateNumber đã tồn tại trong driver_db (chốt T09) |
| 409 | `COMMAND_CONFLICT` | Cùng `commandId`, request hash khác (internal) |
| 409 | `STALE_VERSION` | `commandVersion` ≤ version đã lưu (internal) |
| 409 | `DRIVER_NOT_AVAILABLE` | Reserve khi driver không AVAILABLE/heartbeat stale (internal) |
| 409 | `TOMBSTONED_RESERVATION` | Reserve tới sau release cho cùng reservationId (internal) |
| 405 | `METHOD_NOT_ALLOWED` | HTTP method không được hỗ trợ cho endpoint (vd DELETE trên endpoint POST-only) |
| 413 | `PAYLOAD_TOO_LARGE` | Body > 64 KiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` request không phải `application/json` |
| 429 | `RATE_LIMITED` | Vượt rate limit ở gateway Nginx (`limit_req`, spec §11) — trả JSON cùng định dạng ApiError (chốt T08-6/T05-11) |
| 500 | `INTERNAL_ERROR` | Lỗi không mong đợi; log đầy đủ phía server, client chỉ nhận code chung |
| 502/503/504 | `UPSTREAM_UNAVAILABLE` | Upstream service không khả dụng / timeout ở gateway Nginx (không phải lỗi nghiệp vụ) — trả JSON cùng định dạng ApiError (chốt T08-3) |

### 1.3. Phân trang (mọi list endpoint)

Query: `page` (0-based, default 0), `size` (default 20, max **100** — lớn hơn bị clamp về 100). Response envelope:

```json
{"content": [ ... ], "page": 0, "size": 20, "totalElements": 37, "totalPages": 2}
```

---

## 2. JWT và credential internal (chốt)

- Thuật toán: **RS256** (private key chỉ ở identity-service; public key phát cho các service khác). Generate dev key bằng `scripts/generate-dev-keys.sh` (T06), key gitignore.
- Claims chốt:

| Claim | Giá trị |
|---|---|
| `sub` | userId (UUID) |
| `email` | email user |
| `role` | `PASSENGER` \| `DRIVER` |
| `iss` | `ridehailing-identity` |
| `aud` | `["identity-service","driver-service","trip-service","matching-service","payment-service"]` — mỗi service verify tên mình nằm trong `aud` |
| `iat`, `exp` | giây epoch; `expiresIn` default **3600s** (env `JWT_EXPIRES_IN_SECONDS`) |

- Verify ở mọi service: chữ ký + allowlist alg `{RS256}` + `iss` đúng + `aud` chứa service mình + chưa hết hạn. Sai → 401 `UNAUTHORIZED`.
- Payload JWT phải là JSON object; mọi phần tử `aud` phải là string, không lọc bỏ phần tử sai kiểu để chấp nhận token. Payload/claims không hợp lệ trả 401.
- Clock skew = **0 giây**: `exp` bắt buộc là số và phải **lớn hơn** giây epoch hiện tại (`exp <= now` bị từ chối); `nbf` nếu có phải đã hiệu lực.
- Internal credential: **một token dev dùng chung** cho mọi cặp service (env `INTERNAL_API_TOKEN`, từ `.env`), header `X-Internal-Token`. Không expose qua Nginx; internal port không publish ra host.
- **Rủi ro chấp nhận (chốt T05-12/N07):** một token dùng chung nghĩa là mọi caller nội bộ (Trip, Matching) đều có thể gọi **mọi** endpoint `/internal/**` của driver-service — không phân quyền theo caller. Mitigations: network Docker isolation (internal port không publish ra host), so sánh constant-time, yêu cầu spec §11; per-caller token là cải tiến tương lai, ngoài MVP.

---

## 3. Public endpoints

Nginx route chốt: `/api/auth/*` → identity :8081 · `/api/drivers/*` → driver :8082 · `/api/trips/*`, `/api/notifications/*` → trip :8083. Matching/payment **không có** public API nghiệp vụ.

### 3.1. identity-service — `/api/auth`

#### POST /api/auth/register (public)
Request:
```json
{"email":"p1@example.com","password":"secret123","fullName":"Passenger One","role":"PASSENGER"}
```
Validation (chốt cập nhật 2026-10-19, đợt sửa F04): email hợp lệ; password **≥ 8 ký tự và ≤ 72 byte UTF-8** (giới hạn đầu vào BCrypt — vượt trả 400 `INVALID_INPUT`, không cắt/trim mật khẩu); fullName **2–100 ký tự sau khi trim**; role bắt buộc ∈ {`PASSENGER`,`DRIVER`} (thiếu/null → 400; client không thể tự đăng ký ADMIN).
- **201** `{"user":{"id":"uuid","email":"p1@example.com","fullName":"Passenger One","role":"PASSENGER"}}`
- 400 `INVALID_INPUT` · 409 `EMAIL_TAKEN`

#### POST /api/auth/login (public)
Request: `{"email":"...","password":"..."}`
- Password > 72 byte UTF-8 → 401 `INVALID_CREDENTIALS` với **cùng message** như mọi trường hợp sai credentials — không lộ việc email có tồn tại, không trả 500 (chốt F04).
- **200**
```json
{
  "accessToken": "<JWT RS256>",
  "expiresIn": 3600,
  "user": {"id":"uuid","email":"p1@example.com","fullName":"Passenger One","role":"PASSENGER"}
}
```
- 401 `INVALID_CREDENTIALS` (cùng message cho email sai và password sai — không lộ chi tiết)

#### GET /api/auth/me (authenticated)
- **200** `{"id":"uuid","email":"...","fullName":"...","role":"PASSENGER"}` · 401 nếu token hỏng

### 3.2. driver-service — `/api/drivers`

Tất cả endpoint dưới đây yêu cầu role `DRIVER` (passenger gọi → 403 `FORBIDDEN`).

#### GET /api/drivers/me
Trạng thái hiện tại của driver đang đăng nhập — nguồn sự thật cho UI khi tải lại. **Chỉ đọc:** không đổi state, không cập nhật heartbeat.
- **200**
```json
{"driverId":"uuid","displayName":"Driver A","vehicle":{"plateNumber":"51A-123.45","vehicleType":"MOTORBIKE"},"status":"RESERVED","latitude":10.7769,"longitude":106.7009,"lastSeenAt":"2026-10-18T09:30:00Z","tripId":"uuid-hoac-null"}
```
- `tripId`: trip đang gắn với driver (`RESERVED`: từ reservation hiện tại; `BUSY`: `current_trip_id`), còn lại `null`. `latitude`/`longitude`/`lastSeenAt` là `null` khi chưa từng cập nhật.
- 404 `NOT_FOUND` khi chưa có profile.

#### POST /api/drivers/me/profile
Request:
```json
{"displayName":"Driver A","vehicle":{"plateNumber":"51A-123.45","vehicleType":"MOTORBIKE"}}
```
Validation: displayName 2–60; plateNumber 3–20 (unique trong driver_db); vehicleType ∈ {`MOTORBIKE`,`CAR`}.
- Lần đầu: **201** `{"driverId":"uuid","displayName":"Driver A","vehicle":{"plateNumber":"51A-123.45","vehicleType":"MOTORBIKE"},"status":"OFFLINE"}`
- Gọi lại cùng user (idempotent theo userId): **200** trả profile đã tạo, **không cập nhật** body mới (chốt: MVP không hỗ trợ sửa profile sau khi tạo).
- Plate trùng của user khác → 409 `PLATE_TAKEN` (chốt T09). User chưa có profile gọi availability/location → 404 `NOT_FOUND`.

#### PUT /api/drivers/me/availability
Request: `{"status":"AVAILABLE"}` hoặc `{"status":"OFFLINE"}`
- **200** `{"driverId":"uuid","status":"AVAILABLE","lastSeenAt":"2026-10-18T09:30:00Z"}` (cập nhật heartbeat `last_seen_at`)
- 409 `STATE_CONFLICT` khi đang `RESERVED`/`BUSY` và yêu cầu OFFLINE (details: state hiện tại + tripId liên quan). Chốt T09: mọi request availability trong khi `RESERVED`/`BUSY` đều 409 (kể cả AVAILABLE — state machine §4 không có transition PUT từ RESERVED/BUSY); status ngoài {AVAILABLE,OFFLINE} → 400 `INVALID_INPUT`.

#### PUT /api/drivers/me/location
Request: `{"latitude":10.7769,"longitude":106.7009}`
- **200** `{"driverId":"uuid","lastSeenAt":"2026-10-18T09:30:05Z"}` — cập nhật tọa độ + heartbeat (`last_seen_at`). Cho phép gọi ở mọi state (kể cả BUSY).
- 400 `INVALID_INPUT` nếu ngoài khoảng/không finite

#### GET /api/drivers/me/offer
Trả đề nghị hiện tại của driver này, **dữ liệu lấy từ reservation local** (reserve command mang sẵn thông tin chuyến — xem §4.2; không đọc Trip DB).
- **200** có offer:
```json
{
  "tripId": "uuid",
  "attempt": 1,
  "pickup": {"latitude": 10.7769, "longitude": 106.7009},
  "destination": {"latitude": 10.8000, "longitude": 106.7200},
  "distanceM": 3316,
  "fareVnd": 32000,
  "expiresAt": "2026-10-18T09:30:35Z"
}
```
- **200** không có offer: body `null` (không dùng 404)
- **Chốt T17 (2026-10-25):**
  - "Offer hiện tại" = reservation là `current_reservation_id` của chính driver gọi API, driver đang `RESERVED`, reservation thuộc driver đó, state `RESERVED` và `expires_at > clock_timestamp()` (đồng hồ DB, cùng nguồn với expiry worker và confirm). Mọi trường hợp khác trả `null`: chưa được đề nghị, đã release/expire (kể cả khi worker chưa kịp chuyển row sang EXPIRED), hoặc đã `CONFIRMED` (đã nhận chuyến — xem chuyến qua `GET /api/trips/{id}`).
  - Body `null` là literal JSON `null` với `Content-Type: application/json`, không phải body rỗng.
  - Driver chưa có profile → 404 `NOT_FOUND` (giống availability/location).
  - `expiresAt` là đúng giá trị Trip gửi trong lệnh reserve, ISO-8601 UTC. Client nên đếm ngược theo giá trị này; server vẫn là nguồn quyết định khi accept (T18).
  - Chỉ đọc: không cập nhật heartbeat, state hay version của driver.

### 3.3. trip-service — `/api/trips`

#### POST /api/trips/quote (PASSENGER)
Request:
```json
{"pickup":{"latitude":10.7769,"longitude":106.7009},"destination":{"latitude":10.8000,"longitude":106.7200}}
```
- **200** `{"distanceM":3316,"fareVnd":32000}` — không tạo trip, không side effect.
- Công thức chốt: `distanceM = round(haversine(pickup, destination))` (meter); `fareVnd = FARE_BASE_VND + ceil(distanceM/1000) * FARE_PER_KM_VND`; default `FARE_BASE_VND=12000`, `FARE_PER_KM_VND=5000` (env). Giá tính phía server, không tin frontend. MVP không enforce khoảng cách tối thiểu.
- 400 `INVALID_INPUT`

#### POST /api/trips (PASSENGER) — bắt buộc header `Idempotency-Key: <UUID>`
Body giống quote.
- **Sửa R01:** key được scope theo `(principal_id, operation=CREATE_TRIP, key)`; passenger khác dùng cùng key có namespace riêng, không được replay response của người trước.
- **Chốt T12-4 (R01):** hash payload idempotency chuẩn hóa double như §4.1: `-0.0` → `"0.0"`, các giá trị khác giữ `Double.toString`; cùng key + latitude `0.0`/`-0.0` là replay 202, không 409 `IDEMPOTENCY_MISMATCH`.
- **202** + header `Location: /api/trips/{tripId}`:
```json
{"tripId":"uuid","state":"SEARCHING","matchingAttempt":1,"createdAt":"2026-10-18T09:30:00Z"}
```
- Cùng key + cùng payload (retry client): trả lại **response gốc** đã lưu (cùng tripId, 202) — không tạo trip mới.
- Cùng key + payload khác: 409 `IDEMPOTENCY_MISMATCH`.
- Thiếu key / key sai định dạng UUID: 400 `INVALID_INPUT`.
- **Chốt T12-6 (N28):** tối đa **một** trip chưa terminal per passenger — nếu passenger đã có trip với `state` ∉ {COMPLETED, CANCELLED, NO_DRIVER} thì create mới trả **409 `ACTIVE_TRIP_EXISTS`**. Kiểm tra chạy trong transaction sau advisory lock theo passenger; claim idempotency key thất bại bị rollback cùng transaction (key không bị tiêu thụ).

#### GET /api/trips (authenticated, phân trang §1.3)
Passenger thấy chuyến của mình; driver thấy chuyến đang offer/assign cho mình.
- **Sửa R02:** driverId là profile ID; JWT sub là identity user ID. Trip lưu `driver_user_id` từ `driverUserId` trong candidate/proposal để kiểm tra quyền. `driver_id` và `driver_user_id` cùng null hoặc cùng có giá trị. Không so trực tiếp driverId với JWT sub, không đọc Driver DB từ Trip.
- **Chốt N22:** `trips.driver_id`/`driver_user_id` ghi khi trip chuyển sang `DRIVER_OFFERED` (để driver được offer xem được trip), xóa về NULL khi reject/expire; assignment hiệu lực chỉ từ ACCEPTED (state-machines.md §6 invariant 2).
`content[]`:
```json
{"tripId":"uuid","state":"SEARCHING","pickup":{"latitude":10.7769,"longitude":106.7009},"destination":{"latitude":10.8000,"longitude":106.7200},"distanceM":3316,"fareVnd":32000,"paymentStatus":"NOT_REQUESTED","createdAt":"...Z"}
```

#### GET /api/trips/{id} (owner hoặc assigned driver)
- **200**
```json
{
  "tripId": "uuid",
  "state": "DRIVER_OFFERED",
  "pickup": {"latitude": 10.7769, "longitude": 106.7009},
  "destination": {"latitude": 10.8000, "longitude": 106.7200},
  "distanceM": 3316,
  "fareVnd": 32000,
  "paymentStatus": "NOT_REQUESTED",
  "driverId": "uuid-hoac-null",
  "matchingAttempt": 1,
  "createdAt": "...Z",
  "updatedAt": "...Z"
}
```
- 404 `NOT_FOUND` nếu không tồn tại hoặc caller không có quyền (chính sách §1.2)
- **Chốt T05-9 (N27):** passenger chỉ thấy `driverId` — MVP **không hiển thị** tên/số xe/ảnh driver; trip-service không đọc Driver DB, không enrich profile.

#### GET /api/trips/{id}/events (owner/assigned driver, phân trang, sắp `version` giảm)
`content[]`:
```json
{"type":"TRIP_CREATED","version":1,"actorId":"uuid-hoac-null","summary":"Trip created in SEARCHING","createdAt":"...Z"}
```
`type` thuộc enum §3.5 (trip event history).

#### POST /api/trips/{id}/cancel (owner = passenger)
Không có body. Chốt hai nhánh:
- Chưa có reservation hoạt động/đang xử lý (state `SEARCHING`, saga không pending reserve): **200** `{"tripId":"...","state":"CANCELLED"}` — saga ABORTED ngay. **Không publish event "hủy matching"** (event này không tồn tại trong catalog — chốt F31); event `DriverProposed`/`NoDriverFound` attempt cũ tới sau đó bị bỏ qua: ACK + log theo events.md §4.4.
- Có reservation pending/hoạt động (`DRIVER_OFFERED` hoặc đang RESERVING): **202** `{"tripId":"...","state":"CANCELLING"}` — saga phase `COMPENSATING`, worker release rồi mới chuyển `CANCELLED`; client poll `GET /trips/{id}`.
- Sau khi accept thắng (`ACCEPTED`/`IN_PROGRESS`/`COMPLETED`) hoặc đã terminal: 409 `STATE_CONFLICT`.
- **Chốt T20 (2026-10-25):**
  - Mọi nhánh đều đi qua `CANCELLING` và chỉ thành `CANCELLED` sau khi mọi reservation liên quan đã terminal phía Driver (release reason `CANCELLED`) — không bao giờ "CANCELLED rồi bỏ quên driver".
  - **SEARCHING:** listener T16 có thể đang reserve dở mà saga chưa thể hiện. Trip release (tạo tombstone) cho **mọi** reservationId tất định của attempt hiện tại, `UUID v3("RESERVE|<tripId>|<attempt>|<index>")` với index `0..MATCHING_MAX_CANDIDATES-1`; reserve tới trễ nhận `TOMBSTONED_RESERVATION`, reservation đã tạo thì được giải phóng. Trip chỉ thử tối đa `MATCHING_MAX_CANDIDATES` candidate để tập này luôn đủ. Release xong inline → **200** `CANCELLED`.
  - **DRIVER_OFFERED** (saga WAITING_DRIVER): release reservation đang offer; xong inline → **202** `CANCELLED`; chưa rõ → **202** `CANCELLING` (gọi lại cancel để tiếp tục; worker tự động là T21).
  - Driver đang accept (saga CONFIRMING) hoặc đang reject (saga COMPENSATING) → 409 `STATE_CONFLICT`. Accept/reject/cancel phân xử bằng khóa row saga: chỉ một intent thắng.
  - Đã `CANCELLED` → **200** `CANCELLED` (idempotent). `NO_DRIVER`/`COMPLETED` → 409.
  - Chỉ role PASSENGER (403 nếu khác); passenger không phải owner → 404 (không lộ trip người khác).
  - Sau khi hủy, `driver_id`/`driver_user_id` về NULL; trip_event `CANCELLING` → (`RESERVATION_RELEASED`) → `TRIP_CANCELLED`.

#### POST /api/trips/{id}/accept (chỉ offered driver)
Request: `{"attempt":1}` — phải khớp attempt của offer hiện tại.
- **202** `{"tripId":"...","state":"ACCEPTED"|"DRIVER_OFFERED"}` — `ACCEPTED` nếu confirm hoàn tất inline; `DRIVER_OFFERED` nếu cần reconcile (confirm timeout, worker tiếp tục). Client poll đến khi terminal hoặc `ACCEPTED`.
- Caller role không phải DRIVER: 403 `FORBIDDEN`.
- Driver khác với người được offer cho trip+attempt đó: 409 `NOT_OFFERED_DRIVER`.
- Offer đã hết hạn/đã release: 409 `STATE_CONFLICT` (details: state).
- **Chốt rà soát T32 (2026-10-30):** chỉ driver từng được offer trip này mới nhận `STATE_CONFLICT` kèm state; driver không liên quan luôn nhận `NOT_OFFERED_DRIVER` (start/complete: `NOT_ASSIGNED_DRIVER`) ở mọi state — không lộ state trip của người khác (cùng tinh thần §1.2).
- **Chốt T18 (2026-10-25):**
  - Trip ghi intent ACCEPT (saga WAITING_DRIVER → CONFIRMING) trong transaction khóa row saga, rồi mới gọi confirm (version 2, commandId `UUID v3("CMD-CONFIRM|<reservationId>")`) ngoài transaction. ACCEPTED chỉ được ghi sau khi Driver xác nhận; saga chuyển `AWAITING_FINISH` (desired `NONE`) chờ complete (T22).
  - Timeout/5xx khi confirm → GET reservation: CONFIRMED khớp → ACCEPTED; vẫn RESERVED → gửi lại confirm một lần cùng commandId; vẫn không rõ → 202 `DRIVER_OFFERED`, intent giữ trong saga; gọi accept lại để tiếp tục. Không bao giờ ghi ACCEPTED khi chưa thấy CONFIRMED.
  - Driver trả 4xx (offer hết hạn/đã release) → 409 `STATE_CONFLICT`, `details.reservationState`; saga về WAITING_DRIVER, trip giữ DRIVER_OFFERED (chuyển SEARCHING khi offer hết hạn là T21).
  - Accept lại sau ACCEPTED (cùng driver + attempt) → 202 `ACCEPTED`, không gọi Driver. Thiếu `attempt` → 400; trip không tồn tại → 404.

#### POST /api/trips/{id}/reject (chỉ offered driver)
Request: `{"attempt":1}`.
- **202** `{"tripId":"...","state":"SEARCHING","matchingAttempt":2}` — release reservation, tăng attempt, thêm driver vào excluded list; nếu đã hết số lần đề nghị tối đa → `"state":"NO_DRIVER"`.
- 409 `NOT_OFFERED_DRIVER` / `STATE_CONFLICT` như accept.
- **Chốt T19 (2026-10-25):**
  - Intent REJECT (saga → COMPENSATING) → release reason `REJECTED` (version 3, commandId `UUID v3("CMD-RELEASE|<reservationId>|<reason>")`). Chỉ khi Driver trả `RELEASED` hoặc `EXPIRED` mới: attempt+1, `excludedDriverIds += driverId`, outbox `TripRequested` attempt mới (phát ngay, không chờ `MATCHING_RETRY_DELAY_MS`), trip_event `OFFER_REJECTED` + `RESERVATION_RELEASED`.
  - `attempt >= MAX_MATCHING_ATTEMPTS` hoặc quá deadline → `NO_DRIVER` (+ trip_event `NO_DRIVER`), saga ABORTED.
  - Release chưa rõ → 202 `{"state":"DRIVER_OFFERED","matchingAttempt":n}`, **không** phát TripRequested; gọi lại reject để tiếp tục.
  - `excludedDriverIds` tích lũy qua các lượt; reject lặp lại cho attempt vừa từ chối → trả state hiện tại (idempotent).

#### POST /api/trips/{id}/start (chỉ assigned driver)
Không body. State phải là `ACCEPTED`.
- **200** `{"tripId":"...","state":"IN_PROGRESS"}`
- 403 role sai · 409 `NOT_ASSIGNED_DRIVER` · 409 `STATE_CONFLICT` (state khác ACCEPTED, kể cả sau CANCELLED — invariant 5)
- **Chốt T22 (2026-10-26):** ghi trip_event `TRIP_STARTED`; saga giữ `AWAITING_FINISH` (desired `NONE`). Start lặp trên IN_PROGRESS → 409 `STATE_CONFLICT`.

#### POST /api/trips/{id}/complete (chỉ assigned driver)
Không body. State phải là `IN_PROGRESS`.
- **200** `{"tripId":"...","state":"COMPLETED","paymentStatus":"PENDING"}` — publish `TripCompleted` qua outbox; saga chờ finish assignment.
- 409 như start.
- **Chốt T22 (2026-10-26):** Tx1 ghi COMPLETED + `payment_status=PENDING`, `payment_attempt=1`, trip_event `TRIP_COMPLETED`, outbox `TripCompleted` `{tripId, attempt:1, amountVnd=fare_vnd, currency:"VND"}` (causation null — HTTP-triggered; correlation = X-Correlation-ID của request) và intent finish bền (saga `AWAITING_FINISH` + desired `FINISH_ASSIGNMENT`). Ngoài tx gọi finish driver (commandVersion 4, commandId tất định theo reservation); thành công → saga COMPLETED terminal, chưa rõ → giữ pending cho worker resume. Response luôn 200 bất kể kết quả finish. Complete lặp trên COMPLETED → 409 `STATE_CONFLICT`, không phát outbox row mới / payment_attempt không đổi.

### 3.4. trip-service — `/api/notifications`

#### GET /api/notifications (authenticated, phân trang, sắp `created_at` giảm)
Chỉ thông báo của chính caller.
`content[]`:
```json
{"notificationId":"uuid","tripId":"uuid","type":"TRIP_OFFERED","message":"Tài xế đã sẵn sàng đón bạn.","readAt":null,"createdAt":"...Z"}
```
- `type` enum chốt: `TRIP_OFFERED`, `OFFER_RECEIVED`, `TRIP_ACCEPTED`, `TRIP_CANCELLED`, `NO_DRIVER`, `TRIP_COMPLETED`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`.
- Cột `read_at` giữ trong schema; **MVP không có endpoint đánh dấu đã đọc** (chốt — tránh mở rộng scope).

### 3.5. Trip event history (`trip_events.type`)

Enum chốt, ghi tại mỗi transition (version = trip.version lúc đó):
`TRIP_CREATED`, `DRIVER_OFFERED`, `OFFER_REJECTED`, `OFFER_EXPIRED`, `RESERVATION_RELEASED`, `TRIP_ACCEPTED`, `TRIP_STARTED`, `TRIP_COMPLETED`, `CANCELLING`, `TRIP_CANCELLED`, `NO_DRIVER`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`.

---

## 4. Internal APIs (chỉ mạng nội bộ, auth `X-Internal-Token`)

### 4.1. Quy tắc command idempotency + versioning (áp dụng mọi internal command)

Mỗi command body mang:
```json
{
  "commandId": "uuid",
  "commandVersion": 3,
  "...": "trường nghiệp vụ"
}
```

- `commandId` ổn định khi retry cùng thao tác. Bảng `processed_commands(command_id unique, request_hash, stored_response, created_at)`:
  - Cùng `commandId` + cùng hash → **replay**: trả lại response đã lưu (status gốc), không thay đổi state.
  - Cùng `commandId` + hash khác → 409 `COMMAND_CONFLICT`.
- **Chốt T10 — định nghĩa `request_hash`:** SHA-256 hex của payload nghiệp vụ canonical — mọi trường command **trừ `commandId` và `commandVersion`** (retry cùng thao tác giữ nguyên hash; bump version không coi là payload khác). `stored_response` = JSON `{"status": <int>, "body": {...}}`; replay trả đúng status/body gốc.
- **Sửa R03/R05 + chốt T11 (2026-10-20):** claim command bằng `INSERT ... ON CONFLICT DO NOTHING` cùng transaction; request thua đợi transaction thắng rồi replay/conflict. Hash lưu = `SHA256(operation + "|" + reservationId + "|" + SHA256(canonicalBody))`; operation là RESERVE/CONFIRM/RELEASE/FINISH; canonicalBody gồm field nghiệp vụ của DTO, trừ commandId/commandVersion. Ràng buộc cả resource và thao tác trước replay. V4 chuyển hash RESERVE cũ sang wrapper mới và giữ response gốc. Khóa transaction theo reservationId xử lý cả row chưa tồn tại; driver CAS vẫn chống giữ chỗ trùng.
- **Sửa R06:** commandVersion hợp lệ trong `[1, 2147483647]`; ngoài khoảng → 400 trước mọi ghi DB. INT được dùng nhất quán, không cast long ngoài miền.
- **Chốt T11-2 (F09):** mỗi bước chỉ được tăng tối đa `MAX_VERSION_STEP = 1000`: `commandVersion > last_command_version + 1000` → 400 `INVALID_INPUT` trước mọi ghi DB. Chặn command đẩy reservation lên INT_MAX khiến không còn version hợp lệ cho các lệnh kế tiếp.
- **Chốt T10-5/T12-4 (R01) — canonical double trong hash:** mọi trường số float của payload được chuẩn hóa trước khi băm: `-0.0` → `"0.0"`; các giá trị khác giữ đúng `Double.toString`. Do đó latitude/longitude `0.0` và `-0.0` cùng hash (replay, không 409), và hash đã lưu cũ vẫn ổn định.
- **Chốt T10 — thứ tự kiểm tra reserve:** (1) dedup commandId (replay/conflict) → (2) tombstone: reservation đã terminal (`RELEASED`/`EXPIRED`) → 409 `TOMBSTONED_RESERVATION` → (3) version check → (4) CAS driver. Replay được kiểm tra **trước** tombstone: retry command gốc sau release trả response đã lưu, Trip reconcile trạng thái thật bằng GET.
- **Chốt T10-2/T10-3 (F10/N29):** `expiresAt` phải nằm trong `(now, now + OFFER_TTL_MAX]` (`OFFER_TTL_MAX = reservation.offer-ttl-max-ms`, default 120000 ms) — ngoài khoảng → 400 `INVALID_INPUT`. Kiểm tra chỉ áp dụng cho reservation **mới**; replay command cũ (kể cả `expiresAt` đã qua) vẫn trả response đã lưu theo §4.1. So sánh dùng cùng nguồn đồng hồ DB (`clock_timestamp()`).
- **Chốt T18–T20 — commandVersion do Trip cấp:** reserve = 1, confirm = 2, release = 3 (finish = 4 ở T22). Confirm tới trễ sau release nhận `STALE_VERSION` nên không bao giờ tạo lại assignment đã hủy.
- `commandVersion`: mỗi reservation có `last_command_version` (khởi tạo 0). Command được chấp nhận khi `commandVersion > last_command_version`; sau đó lưu giá trị lớn nhất. Ngược lại → 409 `STALE_VERSION`. Không dùng timestamp để suy luận thứ tự.
- **Chốt N24 — release mọi reservationId bị bỏ:** Trip **phải** gửi `release` (tạo tombstone nếu release tới trước) cho mọi `reservationId` đã cấp mà không dùng nữa — kể cả sau khi nhận 409/timeout HTTP; chỉ coi cleanup xong khi có xác nhận terminal. Không tái dùng `commandId` cho thao tác khác; command lỗi (4xx/5xx/timeout) **không được lưu** để replay.

### 4.2. driver-service — candidates & reservations

#### GET /internal/drivers/candidates
Query: `lat`, `lng` (bắt buộc, hợp lệ), `radiusM` (default 5000; phải > 0), `limit` (mặc định 5; < 1 → 400; > 5 clamp về 5 — chốt T09).
- **Chốt sửa T15 (2026-10-26):** tham số tùy chọn `excludeDriverIds` (danh sách driverId profile, phân cách bằng dấu phẩy, tối đa 50 → quá thì 400). Driver bị loại trừ được bỏ **trong SQL, trước** `ORDER BY … LIMIT`. Trước đây matching nhận top-5 rồi mới lọc phía mình, nên tài xế vừa từ chối (thường là người gần nhất) chiếm chỗ trong top-5 và có thể gây `NoDriverFound` dù vẫn còn tài xế rảnh ở xa hơn. Matching vẫn lọc lại phía mình, trip cũng lọc lại lần nữa (phòng thủ).
- **200**
```json
{"candidates":[{"driverId":"profile-uuid","driverUserId":"identity-user-uuid","distanceM":412}]}
```
Chỉ driver `AVAILABLE` + heartbeat còn mới (`last_seen_at` trong `HEARTBEAT_STALE_AFTER_MS=15000`). Sắp **deterministic**: `distanceM` tăng, tie-break `driverId` (lexicographic UUID).
- Response chỉ mang ID + khoảng cách — không tên/số xe (chốt T05-9/N27); consumer không enrich profile để hiển thị cho passenger trong MVP.

#### PUT /internal/reservations/{reservationId} — RESERVE
Body:
```json
{
  "commandId": "uuid",
  "commandVersion": 1,
  "tripId": "uuid",
  "driverId": "uuid",
  "attempt": 1,
  "expiresAt": "2026-10-18T09:30:35Z",
  "pickup": {"latitude": 10.7769, "longitude": 106.7009},
  "destination": {"latitude": 10.8000, "longitude": 106.7200},
  "distanceM": 3316,
  "fareVnd": 32000
}
```
Thực thi **nguyên tử** trong một transaction driver_db: tạo/kiểm tra reservation (state `RESERVED`, lưu sẵn pickup/destination/distance/fare để phục vụ `GET /drivers/me/offer`) + CAS `UPDATE drivers SET status='RESERVED', current_reservation_id=? WHERE id=? AND status='AVAILABLE' AND last_seen_at > clock_timestamp() - make_interval(secs => ?)`; affected rows ≠ 1 → rollback toàn bộ. Cutoff heartbeat đánh giá bằng DB clock **sau khi có khóa row** (chốt T10-1/T10-4): request chờ lock vượt threshold thì heartbeat coi là stale.
- **201** (lần đầu). Replay cùng `commandId` + hash trả lại **response đã lưu với status gốc 201** theo quy tắc §4.1 — không phải 200 (chốt F06: một thao tác replay có đúng một quy định về HTTP status/body; sửa mâu thuẫn giữa §4.1 và dòng này). Body: `{"reservationId":"uuid","state":"RESERVED","version":1}`
- 409: `DRIVER_NOT_AVAILABLE` · `STALE_VERSION` · `COMMAND_CONFLICT` · `TOMBSTONED_RESERVATION` (release đã tới trước cho reservationId này — tombstone xem §state-machines)

#### GET /internal/reservations/{reservationId}
- **200** `{"reservationId":"uuid","state":"RESERVED","tripId":"uuid","driverId":"uuid","attempt":1,"expiresAt":"...Z","version":1}`
- 404 nếu chưa từng biết reservationId

#### POST /internal/reservations/{id}/confirm
Body: `{"commandId":"uuid","commandVersion":2,"tripId":"uuid","driverId":"uuid","attempt":1}`
Chỉ khi offer còn hạn, state `RESERVED` và tripId/driverId/attempt khớp; cùng transaction chuyển driver → `BUSY`, `current_trip_id=tripId`. CAS phải khớp current_reservation_id và driver còn RESERVED.
- **200** `{"reservationId":"...","state":"CONFIRMED"}` (idempotent replay OK)
- 409: `STATE_CONFLICT` (sai trip/attempt hoặc state không phải RESERVED) · `STALE_VERSION` · `COMMAND_CONFLICT`

#### POST /internal/reservations/{id}/release
Body: `{"commandId":"uuid","commandVersion":2,"reason":"CANCELLED"}` — reason ∈ {`CANCELLED`,`REJECTED`,`OFFER_EXPIRED`}
Chuyển terminal `RELEASED` + `terminal_reason`; giải phóng driver → `AVAILABLE` **chỉ khi** `current_reservation_id` vẫn trỏ đúng reservation này (fencing).
- **200** `{"reservationId":"...","state":"RELEASED"}` (idempotent)
- Release tới trước reserve: tạo tombstone — chèn reservation ở trạng thái terminal `RELEASED` để reserve trễ nhận 409 `TOMBSTONED_RESERVATION`.
- Tombstone chỉ biết id/version/reason; các field trip/driver/attempt/expiry/route/fare là null, không dùng UUID hay giá trị giả. GET trả null cho field chưa biết.
- **Chốt T11-3 (N30):** release reservation `CONFIRMED` chỉ chấp nhận reason `CANCELLED`; `REJECTED`/`OFFER_EXPIRED` trên CONFIRMED → 409 `STATE_CONFLICT` (driver giữ BUSY). Release được phép bù trừ cả CONFIRMED. Driver BUSY phải khớp cả current_reservation_id và current_trip_id trước khi giải phóng. Row terminal giữ nguyên state/reason; command mới version cao hơn trả 200 state terminal hiện tại (EXPIRED giữ EXPIRED), nâng last_command_version. Retry cùng command replay trước version/state check.

#### POST /internal/reservations/{id}/finish
Body: `{"commandId":"uuid","commandVersion":4,"tripId":"uuid"}`
Sau khi trip COMPLETED: chỉ từ state `CONFIRMED` (khớp tripId) → terminal `RELEASED`, `terminal_reason=TRIP_COMPLETED`; giải phóng driver → `AVAILABLE` nếu `current_trip_id` khớp.
- **Chốt T22 (2026-10-26):** commandVersion = 4; Trip gọi finish sau complete với commandId tất định `UUID v3("CMD-FINISH|<reservationId>")`; reconcile bằng GET — `RELEASED` khớp tripId coi là đã xong.
- **200** `{"reservationId":"...","state":"RELEASED"}` (idempotent — retry sau crash an toàn)
- Fence phải khớp cả current_reservation_id và current_trip_id; finish cũ không ảnh hưởng assignment mới. Row RELEASED/TRIP_COMPLETED cùng trip cho phép command mới version cao hơn trả 200 mà không giải phóng lại driver; terminal do lý do khác → 409 STATE_CONFLICT.
- Confirm/finish chưa biết reservation → 404; sai state/identity hoặc fencing confirm → 409 STATE_CONFLICT. Release/finish cũng áp dụng STALE_VERSION/COMMAND_CONFLICT của §4.1.
- Worker expiry scan batch tối đa 100, fixed delay mặc định 1000 ms, transaction + CAS state=RESERVED và expires_at<=now. Chuyển EXPIRED/OFFER_EXPIRED và giải phóng bằng fencing; giữ last_command_version, không tự cấp external command version. CONFIRMED không được expire. Config: reservation.expiry.enabled / reservation.expiry.fixed-delay-ms (env RESERVATION_EXPIRY_ENABLED / RESERVATION_EXPIRY_FIXED_DELAY_MS).

---

## 5. Correlation ID (chốt)

1. Mọi request public/internal mang `X-Correlation-ID`. Giá trị hợp lệ = UUID; **thiếu hoặc sai định dạng → server tự sinh UUID mới** (không reject request).
2. Service truyền tiếp header này sang mọi HTTP call ra ngoài (internal calls, Nginx entrypoint tạo nếu client không gửi). Gateway Nginx giữ UUID hợp lệ do client gửi; nếu không có thì sinh từ `$request_id` (32 hex ngẫu nhiên của Nginx) định dạng lại thành UUID 8-4-4-4-12 (chốt L12). Truyền cho backend qua `proxy_set_header X-Correlation-ID` và ghi vào access log. Service canonicalize giá trị (UUID chuẩn hóa — T07-10/N13) và echo lại trong response header + body lỗi.
3. Outbox event mang `correlationId` của request gốc khởi tạo luồng; consumer log bằng `event.correlationId`. Event hồi phục/replay giữ correlation gốc.
4. Mọi response lỗi luôn chứa `correlationId`.

---

## 6. Tham số demo (env, chốt default)

| Env | Default | Ý nghĩa |
|---|---:|---|
| `FARE_BASE_VND` | 12000 | Giá gốc mỗi chuyến |
| `FARE_PER_KM_VND` | 5000 | Giá mỗi km (làm tròn lên) |
| `OFFER_TTL_SECONDS` | 30 | Thời hạn offer (reservation RESERVED) |
| `RESERVATION_OFFER_TTL_MAX_MS` | 120000 | Trần tối đa của `expiresAt` khi reserve (`(now, now + max]`) — chốt T10-2 |
| `HEARTBEAT_STALE_AFTER_MS` | 15000 | Heartbeat cũ hơn → không chọn cho chuyến mới |
| `MATCHING_MAX_CANDIDATES` | 5 | Số ứng viên mỗi lần đề nghị |
| `MAX_MATCHING_ATTEMPTS` | 3 | Số lần matching tối đa mỗi trip |
| `MATCHING_RETRY_DELAY_MS` | 5000 | Delay trước attempt matching kế tiếp sau NoDriverFound/hết candidate (chốt N21) |
| `TRIP_MATCH_DEADLINE_SECONDS` | 120 | Deadline tổng tìm tài xế → NO_DRIVER |
| `JWT_EXPIRES_IN_SECONDS` | 3600 | Thời hạn JWT |

---

## 7. Quyết định chốt — giải quyết các lựa chọn "A hoặc B" còn mơ hồ

| # | Vấn đề | Quyết định | Lý do |
|---:|---|---|---|
| 1 | DriverProposed mang `driverId` hay `candidateIds`? | **Danh sách có thứ tự** `candidates:[{driverId,driverUserId,distanceM}]` (max 5) — Trip thử lần lượt theo rank | Fallback candidate kế tiếp khi reserve conflict; giữ ánh xạ profile/identity để Trip kiểm tra quyền; thứ tự deterministic ở §4.2 |
| 2 | Cancel trả 200 hay 202? | Có reservation pending → **202** + state `CANCELLING` (transient); không có reservation → **200** + `CANCELLED` ngay | Khớp spec "202 nếu đang bù trừ"; UI hiển thị "đang hủy" đến khi reconcile xong |
| 3 | Accept trả gì khi confirm inline thành công? | Luôn **202**, body.state phản ánh state sau thao tác (`ACCEPTED` hoặc `DRIVER_OFFERED`) | Contract đồng nhất; client poll, không giả định kết quả từ status code |
| 4 | Offer DTO lấy dữ liệu chuyến từ đâu? | **Reservation row** (reserve command mang pickup/destination/distanceM/fareVnd) | Driver-service không đọc Trip DB (yêu cầu đề); nguồn sự thật state chuyến vẫn là trip-service |
| 5 | State terminal của reservation sau finish? | `RELEASED` + cột mới `terminal_reason` ∈ {`CANCELLED`,`REJECTED`,`OFFER_EXPIRED`,`TRIP_COMPLETED`} | Giữ đúng 4 state đã chốt (RESERVED/CONFIRMED/RELEASED/EXPIRED) mà vẫn phân biệt lý do terminal |
| 6 | Sai quyền: 403 hay 404? | GET resource không có quyền → **404**; action sai role → **403**; state conflict → **409** | Chính sách nhất quán, không lộ tồn tại resource người khác (S01) |
| 7 | Chấp nhận commandVersion thế nào? | Chỉ khi `commandVersion > last_command_version` đã lưu | Chống xử lý ngược thứ tự; retry cùng command dùng replay hash, không cần version mới |
| 8 | Internal auth mechanism? | Header `X-Internal-Token`, token dev dùng chung từ env, so constant-time | Đơn giản cho MVP, tách khỏi JWT user; **rủi ro chấp nhận**: mọi caller nội bộ gọi được mọi `/internal/**` của driver-service — mitigations: Docker network isolation, không expose public, spec §11 (per-caller token = cải tiến tương lai) |
| 9 | Số payment attempt lưu ở đâu? | Cột `trips.payment_attempt` int default 0; tăng lên 1 khi phát TripCompleted. MVP: FAILED là terminal — **không có cơ chế cấp attempt mới sau FAILED** (spec §15); retry kỹ thuật nằm trong cùng attempt (spec §10) | Payment event mang `attempt`; unique(trip_id, attempt) chống thu trùng |
| 10 | Notification mark-as-read? | **Không** làm endpoint trong MVP (giữ cột `read_at`) | Giữ đúng danh sách endpoint spec §8 |

---

## 8. Coverage checklist — ánh xạ đầy đủ spec mục 8–9

### Public endpoints (spec §8) → mục tài liệu
| Endpoint | Mục |
|---|---|
| POST /api/auth/register · login · GET me | §3.1 |
| POST /api/drivers/me/profile · PUT availability · PUT location · GET offer | §3.2 |
| POST /api/trips/quote · POST trips · GET trips · GET {id} · GET {id}/events · cancel · accept · reject · start · complete | §3.3 |
| GET /api/notifications | §3.4 |

### Internal endpoints (spec §8) → mục tài liệu
| Endpoint | Mục |
|---|---|
| GET /internal/drivers/candidates | §4.2 |
| PUT /internal/reservations/{id} · GET {id} · confirm · release · finish | §4.2 |

### Events (spec §9) → `docs/events.md`
TripRequested, DriverProposed, NoDriverFound, TripCompleted, PaymentSucceeded, PaymentFailed — đủ 6/6 trong events.md §3.

Không còn lựa chọn "A hoặc B" chưa chốt: mọi DTO, mã lỗi, thứ tự candidate, nhánh status code và tham số đã có giá trị cụ thể ở trên.
