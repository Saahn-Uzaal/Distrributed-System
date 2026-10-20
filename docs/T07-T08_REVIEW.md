# Đánh giá T07–T08 — 19/10/2026

## Kết luận

T07 và T08 đã có phần lớn nền tảng, nhưng **chưa nên chốt gate T01–T08 hoàn tất**. Lõi JWT/role/internal credential chạy được; frontend build và luồng auth qua Nginx hoạt động. Còn lỗi route, phạm vi bảo vệ endpoint, xử lý phiên frontend và các điểm chưa khớp contract.

Đây là kết quả đánh giá; chưa sửa mã nguồn triển khai trong lượt review này. Các test chẩn đoán bổ sung chỉ được tạo trong bản sao tạm, không thêm vào repo.

| Task | Đánh giá |
|---|---|
| T07 — Security và log tối thiểu | Lõi xác thực và kiểm tra quyền đạt; còn bỏ sót endpoint notifications, log chưa đúng định dạng và test vẫn phụ thuộc DB dev. |
| T08 — Frontend skeleton | Build và luồng register/login/me đạt; còn redirect sai URL API gốc, xử lý phiên hết hạn và validation frontend chưa khớp backend. |

## Kiểm tra đã thực sự chạy

Build/test được chạy trên bản sao tạm của mã nguồn để giữ phiên demo đang chạy:

- Backend `mvnw.cmd -B clean verify`: **BUILD SUCCESS**, **41 tests, 0 failures/errors/skipped** — Identity 16, Driver 11, Trip 8, Matching 3, Payment 3.
- Frontend từ lockfile: `npm ci`, `npm run build` và `npm test` thành công; **2 test hiện có pass**. Hai test này chỉ kiểm tra `routeForRole`, chưa kiểm tra guard/session/form.
- Nginx đang chạy: `nginx -t` thành công.
- HTTP qua Nginx hiện tại: register 201, login 200, `/api/auth/me` 200; passenger gọi Driver API nhận 403; thiếu JWT nhận 401 và correlation ID được giữ trong error body.
- User thử được tạo với email UUID riêng và đã xóa đúng bản ghi đó sau kiểm tra.
- Chạy một Trip instance riêng trên port 18083 để probe filter; tiến trình được dừng sau review.
- Không có browser khả dụng qua kết nối công cụ trong phiên này. Chưa kiểm tra E2E bằng trình duyệt thật; phần UI được đối chiếu source và test render component.

Lưu ý: bản sao mã nguồn không đồng nghĩa test đã cô lập DB. Bốn service ngoài Identity vẫn kết nối DB dev trong lần build trên; đây là một phát hiện bên dưới.

## R01 — Nginx chuyển hướng URL API gốc thay vì proxy nguyên request

**Mức ưu tiên:** cao; T08.

**Tệp:** [nginx.conf](../deploy/nginx/nginx.conf), các location `/api/trips/` và `/api/notifications/`.

Probe tắt tự động follow redirect cho kết quả:

| Request | Kết quả |
|---|---|
| `GET /api/trips` | 301, Location `/api/trips/` |
| `POST /api/trips` | 301, Location `/api/trips/` |
| `GET /api/notifications` | 301, Location `/api/notifications/` |

Contract dùng URL không có slash cuối cho tạo/list trips và notifications. Nginx chỉ có location proxy kết thúc bằng slash nên tự chuyển hướng URL gốc. Client có thể đổi POST thành GET sau 301; URL cuối cũng khác mapping đã chốt.

Khi follow redirect ở môi trường hiện tại, các request tới Trip nhận 502 vì chưa có Trip instance chạy trên upstream 8083. Đây là hiện trạng service đang chạy, không phải bằng chứng riêng của lỗi handler nghiệp vụ. Lỗi cấu hình đã được xác nhận độc lập bằng response 301.

**Cần sửa:** proxy cả URL chính xác và các subpath, giữ nguyên method/body/URI theo contract; không chỉ đổi frontend sang slash cuối để né lỗi. Thêm kiểm tra GET/POST tại `/api/trips` và GET `/api/notifications` qua Nginx.

## R02 — Filter Trip bỏ sót chính endpoint `/api/notifications`

**Mức ưu tiên:** cao đối với phạm vi bảo vệ endpoint; T07.

**Tệp:** [ApiSecurityFilter.java](../src/trip-service/src/main/java/com/ridehailing/trip/security/ApiSecurityFilter.java).

Filter kiểm tra `uri.startsWith("/api/notifications/")` nhưng không kiểm tra `uri.equals("/api/notifications")`.

Probe trực tiếp Trip, không gửi JWT:

| Path | Status/code |
|---|---|
| `/api/trips` | 401 UNAUTHORIZED |
| `/api/notifications/` | 401 UNAUTHORIZED |
| `/api/notifications` | 404 NOT_FOUND |

404 hiện tại cho thấy request đi qua filter tới dispatcher; controller notifications chưa tồn tại nên chưa có dữ liệu bị lộ. Khi thêm controller đúng path của contract ở T25, path này sẽ không được filter kiểm tra JWT nếu giữ nguyên code.

**Cần sửa:** bảo vệ cả path gốc và subpath bằng matcher nhất quán; thêm probe cho URL chính xác trong contract, không chỉ URL có slash cuối.

## R03 — Giao diện báo đăng nhập thành công khi server không xác thực phiên

**Mức ưu tiên:** vừa; T08.

**Tệp:** [useMe.ts](../src/web/src/hooks/useMe.ts), [App.tsx](../src/web/src/App.tsx), [PassengerHome.tsx](../src/web/src/pages/PassengerHome.tsx), [DriverHome.tsx](../src/web/src/pages/DriverHome.tsx).

- `useMe` nuốt lỗi `/me`, để `user = null` và kết thúc loading.
- Guard chỉ kiểm tra session lưu ở client; không xóa session khi nhận 401.
- Hai trang home fallback sang user cũ trong session và vẫn hiển thị “Đăng nhập thành công”.

Test render bổ sung trong bản sao tạm mô phỏng trạng thái `/me` đã hoàn tất nhưng không có user xác thực, trong khi cache còn session. Cả hai trang vẫn render thông báo đăng nhập thành công; **2 assertion bổ sung thất bại**. Đây là test component với kết quả hook được mô phỏng, không phải E2E browser.

**Cần sửa:** phân biệt lỗi 401 với lỗi mạng/server; khi 401 thì xóa phiên và điều hướng login. Không báo xác thực thành công từ dữ liệu cache sau khi server từ chối; đồng bộ thông tin/role với kết quả `/me` hợp lệ.

## R04 — Test security vẫn dùng PostgreSQL dev

**Mức ưu tiên:** vừa; T07.

**Tệp:** các `SecurityProbeTest`/`SecuritySmokeTest` ở Driver, Trip, Matching, Payment; [FlywayConfig.java](../src/driver-service/src/main/java/com/ridehailing/driver/config/FlywayConfig.java).

Nhật ký T07 nói 4 service này exclude JDBC/Flyway để test không cần DB. Nhưng log của lần chạy mới xác nhận:

```text
driver-service   → jdbc:postgresql://localhost:5432/driver_db
trip-service     → jdbc:postgresql://localhost:5432/trip_db
matching-service → jdbc:postgresql://localhost:5432/matching_db
payment-service  → jdbc:postgresql://localhost:5432/payment_db
```

Hikari và custom Flyway bean vẫn được khởi tạo. Để kiểm chứng, chạy riêng test health Driver với datasource trỏ tới port không có DB:

```powershell
.\mvnw.cmd -B -pl driver-service '-Dtest=SecurityProbeTest#healthIsOpenWithoutAuth' '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/no-db' '-Dspring.datasource.hikari.connection-timeout=1000' '-Dspring.datasource.hikari.initialization-fail-timeout=1' test
```

Kết quả: **1 test, 1 error**, không load được ApplicationContext do kết nối PostgreSQL thất bại. Không dừng PostgreSQL đang dùng để làm probe này.

**Cần sửa:** cấu hình test theo auto-configuration thực tế của Boot 4 và xử lý custom Flyway bean, hoặc dùng DB Testcontainers riêng và ghi đúng dependency. Không dựa vào DB dev đang chạy trên máy để tuyên bố test độc lập với DB.

## R05 — Log chưa đúng yêu cầu structured JSON

**Mức ưu tiên:** vừa; T07.

**Tệp:** `CorrelationIdFilter.java` và `application.yml` của các service.

Log runtime mới vẫn là text mặc định của Spring, ví dụ:

```text
2026-10-19T21:30:59.675+07:00 INFO ... request method=GET path=/api/notifications status=404 correlationId=...
```

Đã có correlation ID, method/path/status, nhưng chưa phải JSON; timestamp đang theo offset +07, thiếu các field đã yêu cầu trong spec mục 11 như instanceId, eventType và durationMs.

**Cần sửa:** cấu hình structured JSON logging thực sự, timestamp UTC và các field tối thiểu; giữ correlation ID xuyên request và không ghi password/token. Bộ test hiện có chưa chứng minh định dạng log hoặc kiểm tra rò rỉ secret trong log backend.

## R06 — Validation frontend không khớp contract đã sửa ở T06

**Mức ưu tiên:** vừa/thấp; T08.

**Tệp:** [RegisterPage.tsx](../src/web/src/pages/RegisterPage.tsx).

- Form ghi mật khẩu “8–72 ký tự” và chỉ dùng min/max độ dài chuỗi. Backend yêu cầu tối đa **72 byte UTF-8**. Ví dụ 25 ký tự `ạ` là 75 byte: qua điều kiện độ dài client nhưng bị backend từ chối.
- fullName vẫn kiểm tra max 100 trên chuỗi thô; tên 100 ký tự kèm khoảng trắng hai đầu bị client chặn dù backend/contract chấp nhận sau trim.

Đây là đối chiếu source/contract; chưa thao tác form thật trong browser ở phiên review này.

**Cần sửa:** dùng byte-length UTF-8 cho password và kiểm tra tên sau trim; cập nhật nhãn/thông báo. Thêm test form cho ASCII, tiếng Việt và ranh giới tên sau trim.

## R07 — Body limit Nginx lệch contract

**Mức ưu tiên:** vừa/thấp; cấu hình đã thêm ở T08.

**Tệp:** [nginx.conf](../deploy/nginx/nginx.conf).

Config đặt `client_max_body_size 1m`, trong khi API contract mục 1 yêu cầu **64 KiB**, vượt trả 413 PAYLOAD_TOO_LARGE.

Probe gửi JSON login chứa password 70.000 ký tự: nhận **401 INVALID_CREDENTIALS** từ ứng dụng, chứng minh body vượt 64 KiB vẫn được nhận qua Nginx.

**Cần sửa:** thống nhất giới hạn với contract và kiểm tra cả status lẫn định dạng JSON của lỗi vượt giới hạn. Các kiểm tra hardening tổng thể vẫn có thể tiếp tục ở T32; cấu hình hiện tại không nên được mô tả là đã khớp contract.

## Thứ tự đề xuất

1. R01 và R02: sửa routing và phạm vi auth trước khi xây thêm endpoint nghiệp vụ.
2. R03: xử lý session bị server từ chối.
3. R04/R05: bảo đảm test tái lập và logging đúng tiêu chí T07.
4. R06/R07: đồng bộ frontend/Nginx với contract, thêm các case biên vào test.

Không tính việc chưa có UI đặt xe hay driver profile/matching/payment handler là lỗi T08: các màn hình placeholder hiện tại đúng phạm vi task. Không yêu cầu triển khai trước các task nghiệp vụ để nghiệm thu T07/T08.

Workspace chưa có `.git`, nên chưa kiểm tra được lịch sử commit/đóng góp hoặc diff theo Git. Các số liệu trên là kết quả review mới, không sửa lại nhật ký triển khai cũ.
