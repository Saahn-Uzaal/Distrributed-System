# Rà soát và sửa T27–T32 — 30/10/2026

Phạm vi: UI hành khách/tài xế, triển khai Docker Compose, test concurrency, recovery, security. Các mục "Chốt thiết kế" trong `docs/tasks/T27.md`…`T32.md` trái với file này (boot probe, cache trạng thái tài xế, key trong image, số liệu C01/C02 cũ) đã được thay thế.

## Thay đổi hành vi cần biết

- **API mới `GET /api/drivers/me`** (contract §3.2): trạng thái tài xế, hồ sơ, vị trí, `tripId`. Chỉ đọc, không đổi state hay heartbeat. UI tài xế dùng API này làm nguồn sự thật thay cho boot probe `PUT availability`.
- **Driver không liên quan gọi accept/reject/start/complete** nay luôn nhận `NOT_OFFERED_DRIVER`/`NOT_ASSIGNED_DRIVER`, không còn `STATE_CONFLICT` kèm state. Driver từng được offer trip đó vẫn nhận `STATE_CONFLICT` như trước. Test `TripRejectIntegrationTest.wrongCallerOrStateIsRejected` được đổi kỳ vọng theo quy tắc này.
- **JWT key không còn trong image**: compose bind-mount read-only; phải chạy `scripts/generate-dev-keys.sh` trước `up`. Linux host đặt `HOST_UID`/`HOST_GID` (`.env.example`).

## Lỗi đã sửa

| Task | Lỗi | Cách sửa | Bằng chứng |
|---|---|---|---|
| T27 | Trang chi tiết dừng polling khi `COMPLETED` dù payment còn `PENDING`, không bao giờ cập nhật kết quả | Dừng theo `isTripSettled` (terminal và payment đã có kết quả) | `TripDetailPage.test`, E2E |
| T27 | Lỗi ngay lần tải đầu: spinner mãi không báo lỗi | Hiện lỗi + nút Thử lại | `TripDetailPage.test` |
| T27 | Sửa tọa độ khi quote đang chạy vẫn hiện giá của tọa độ cũ | Bỏ kết quả quote của tọa độ cũ | `CreateTripForm.test` |
| T27 | Idempotency-Key bị bỏ khi 502/504, dù kết quả chưa rõ (Nginx timeout 3 s đến trước timeout client 5 s) | Giữ key cho mọi lỗi chưa rõ kết quả | `CreateTripForm.test` |
| T27 | Nhãn "Mới" hiện vĩnh viễn (MVP không có đánh dấu đã đọc) | Bỏ nhãn | — |
| T27/T28/T29 | Token hết hạn hoặc polling lỗi không báo, màn hình giữ dữ liệu cũ | `useUnauthorizedRedirect`, `StaleDataAlert`; `usePolling` luôn dừng khi `UNAUTHORIZED` | `DriverHome.test`, E2E |
| T28 | Tải lại khi có offer, offer kết thúc, nhận chuyến mới: panel vẫn báo "Không tìm thấy", không bấm được bắt đầu/hoàn thành | Panel theo state server, `key` theo `tripId` | `DriverHome.test`, E2E |
| T28 | Mở trang có thể đổi trạng thái tài xế (tab mới bật `AVAILABLE`); cache lẫn giữa các tài xế cùng tab | Đọc `GET /api/drivers/me`, bỏ cache | Integration test driver, `DriverHome.test`, E2E |
| T28 | Offer hết hạn đúng lúc driver-service chưa giải phóng: UI kẹt `RESERVED`, heartbeat dừng | Poll state server mỗi 3 s | `DriverHome.test` |
| T28 | Accept timeout/5xx dù server đã confirm: chuyến biến mất khỏi màn hình | Lỗi chưa rõ không coi là thất bại; state server quyết định | `DriverHome.test` |
| T28 | Tải lại ghi đè vị trí server bằng tọa độ mặc định; heartbeat gửi cả tọa độ đang gõ dở | Heartbeat chỉ gửi vị trí đã lưu; ô nhập là bản nháp | `DriverHome.test`, E2E |
| T28 | Heartbeat lỗi không báo; vừa về `AVAILABLE` phải chờ 10 s mới có heartbeat | Cảnh báo trên UI; gửi ngay khi về `AVAILABLE` | `DriverHome.test` |
| T29 | Private key JWT trong image, có ở cả 5 container, chạy root | Bind-mount đúng key cho từng service; `USER 10001` | `docker exec`: private chỉ ở identity; public ở identity/driver/trip; matching/payment không có |
| T29 | **Clone mới không build được**: `COPY src/web/public/` trong khi thư mục rỗng không có trong git | Bỏ dòng `COPY` | Bản sao sạch, xem dưới |
| T29 | `.env` không có tác dụng với `FARE_*`, `RESERVATION_EXPIRY_*`, `JWT_EXPIRES_IN_SECONDS`, `PAYMENT_SIMULATE_FAIL_RATE`, `TRIP_MATCH_DEADLINE_SECONDS` | Compose truyền vào container | `printenv` trong container |
| T29 | Nginx crash `host not found` khi khởi động trước backend; IP cũ sau khi backend recreate | `upstream … resolve` + resolver Docker + `depends_on` | `nginx -t` không cần mạng; đổi IP `.10`→`.11` vẫn thông |
| T29 | Không có `.dockerignore`; image chưa pin bản đầy đủ; README mục dev cũ lỗi thời | Thêm `.dockerignore`; `maven:3.10.0-eclipse-temurin-21`, `eclipse-temurin:21.0.13_9-jre`; sửa README | Build từ bản sao sạch |
| T30 | C02 phần lớn không phải race: `sleep "0.$jitter"` khiến 6 thành 600 ms | Jitter 0–49 ms; ghi thời điểm gửi và thời gian xử lý; FAIL nếu không lượt nào chồng nhau | 9/20 lượt chồng nhau |
| T30 | C01: `drivers.status='RESERVED' ≤ 1` luôn đúng khi chỉ một driver online; không có jitter dù tài liệu ghi có; không kiểm tra orphan | Đếm reservation active của driver; kiểm tra khoảng sống không chồng nhau (không phụ thuộc mẫu); kiểm tra orphan; thêm jitter | Xem dưới |
| T30 | C01/C02 chỉ nhận cancel 202, báo fail giả khi cancel từ `SEARCHING` trả 200 | Nhận 200 và 202 | — |
| T31 | R02 `--build` làm payment-service chạy image khác 4 service còn lại | `--no-build` | 5 container cùng image |
| T31 | R01/R02 không khôi phục service khi fail giữa chừng | `trap` EXIT | — |
| T31 | R01 Phase B chỉ gặp `NoDriverFound`, chưa thử `DriverProposed` đến muộn | Giữ heartbeat tươi để matching đề xuất thật; kiểm tra không tạo reservation | `stale_event_type=DriverProposed`, 0 reservation |
| T31 | R01 Phase A phụ thuộc thời gian: heartbeat quá 15 s trong lúc matching khởi động, trip về `NO_DRIVER` (lỗi script, hệ thống xử lý đúng) | Heartbeat suốt lúc khởi động | Lộ ra khi chạy lại; sau sửa PASS |
| T32 | Snapshot "dữ liệu không đổi" chỉ đếm theo state | md5 từng row | S01 |
| T32 | Thiếu token giả mạo và owner trên API mutating | P10–P17 | S01 17/17 |
| T32 | Driver lạ biết được state trip người khác qua lỗi 409 | Kiểm tra quyền trước state | `strangerDriverLearnsNothingAboutTripState`, S01 |
| T32 | S02 `bad_rows` chỉ xét `pickup_lat` | Xét đủ 4 tọa độ | S02 |

## Xác minh (30/10/2026)

- `./mvnw -B clean verify`: **BUILD SUCCESS, 375 tests, 0 failures** (identity 38, driver 123, trip 155, matching 26, payment 33).
- `npm test`: **98/98**; `npm run build` pass. Component test mới được kiểm tra bằng cách đưa lại từng lỗi cũ: mỗi lần đều có test fail đúng chỗ. Khi máy bận, test (cả Login/Register cũ) từng fail ngẫu nhiên do render antd vượt timeout 5 s; đã nới timeout chung và chờ nút hết loading trước khi bấm, sau đó 16/16 lượt chạy song song đều đạt.
- `tests/e2e/ui-flow.cjs` trên headless Chrome qua Nginx :8080: **13/13**.
- **C01** 20/20: `max_concurrent_offered=1`, reservation active tối đa 1, 0 cặp chồng nhau, 0 orphan; trip A thắng 17, trip B thắng 3.
- **C02** 20/20: đúng một intent thắng; accept thắng 14, cancel thắng 6; **9/20** lượt hai request thực sự chồng nhau trên server.
- **R01** PASS: Phase A `depth=1` → `DRIVER_OFFERED`; Phase B `DriverProposed` đến muộn bị bỏ qua, 0 reservation.
- **R02** PASS: 1 payment, 1 inbox, 1 outbox event, 1 trip_event `PAYMENT_*`, `SUCCEEDED`.
- **S01** 17/17, dữ liệu từng row không đổi. **S02** 7/7, `bad_rows=0`.
- **Bản sao sạch**: chỉ file không bị gitignore (422 file), `.env.example`, key mới, `build --no-cache`, volume riêng: build 65 s, healthy 8/8 sau 56 s, Flyway 8 migration, seed + smoke PASS, `/actuator` và `/internal` 404 qua :8080.

## Giới hạn

- C01: matching xử lý `TripRequested` tuần tự nên hai reserve không chạm nhau ở tầng hệ thống; race CAS reserve do `ReserveIntegrationTest` kiểm tra. Snapshot vẫn lấy mẫu ~2 s; kiểm tra khoảng sống reservation bù cho phần này.
- C02: 11/20 lượt hai request không chồng nhau trên server (thứ tự đến bị tách bởi jitter); các lượt này kiểm tra tuần tự, không phải race.
- E2E chưa nằm trong `npm test` hay CI: `puppeteer-core` không phải dependency của project; đưa vào CI thuộc T34.
- Nút Nhận chuyến vẫn dựa vào đồng hồ client để disable khi offer hết hạn; lệch đồng hồ lớn có thể disable sớm, tài xế chờ offer tiếp theo.
- `HOST_UID`/`HOST_GID` trên Linux mới theo tài liệu, chưa chạy thử; toàn bộ kiểm chứng ở trên trên Docker Desktop Windows.
