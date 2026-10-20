# Rà soát toàn bộ dự án DS03 — 20/10/2026

## Kết luận và phạm vi

Repo vẫn còn vấn đề sau đợt sửa R01–R07 và T11. Lượt này ghi nhận **32 phát hiện trong code/config/contract hiện có**, **5 rủi ro hoặc điểm cần chốt**, các khoảng trống kiểm chứng, cùng **đủ 24 task T13–T36 còn TODO**. Không đặt giới hạn số phát hiện; các lỗi chung giữa nhiều service được gộp theo nguyên nhân.

Ưu tiên đầu tiên là **F01: bỏ qua xác thực bằng biến thể URL**, đã thực hiện được reserve không có internal token trên ứng dụng Driver thật với DB test. Các phát hiện mới không đồng nghĩa toàn bộ bản sửa trước đã mất tác dụng: owner-scoped replay, identity mapping, atomic claim và fencing đã sửa vẫn có test pass; phạm vi bảo vệ URL và các tình huống biên khác còn thiếu.

**D** = đã tái hiện bằng HTTP, DB, appender, mã nguồn thực thi hoặc công cụ kiểm tra. **S** = xác nhận qua code/contract, chưa tái hiện độc lập toàn bộ luồng. P1 cần xử lý trước khi cho truy cập backend; P2 ảnh hưởng tính đúng/an toàn/khả năng phục hồi; P3 ảnh hưởng trường hợp biên, chẩn đoán hoặc tính nhất quán triển khai.

Đã đọc mã nghiệp vụ Identity/Driver/Trip, security/error/correlation/logging của cả 5 service, migrations, frontend, Compose/Nginx, script PostgreSQL/RabbitMQ/keys, POM/package metadata, tests và tài liệu. Đối chiếu đặc tả DS03 và PDF đề môn học đã cung cấp. Chỉ rà soát, không sửa implementation/config trong lượt này; các probe bổ sung chạy ở TEMP và PostgreSQL Testcontainers riêng, không dùng DB dev.

## 1. Toàn bộ phát hiện trong phần hiện có

### Xác thực và dữ liệu đầu vào

| ID | Ưu tiên / bằng chứng | Phát hiện và ảnh hưởng | Vị trí chính |
|---|---|---|---|
| F01 | **P1 / D** | Filter so sánh URI thô, MVC lại decode/bỏ matrix parameters. URL biến thể bỏ qua internal auth; đã reserve 201 và đổi driver thành RESERVED khi không có token. Các path public cũng có thể bỏ qua filter; quote không cần principal ở controller. | `driver-service/.../security/ApiSecurityFilter.java:51,57`; Trip `ApiSecurityFilter.java:47`; Identity `JwtAuthFilter.java:39` |
| F02 | P2 / D | JWT dùng longValue() làm mất phần lẻ NumericDate: nbf tương lai trong cùng giây được chấp nhận sớm, exp tương lai trong cùng giây bị từ chối sớm; nbf:null được coi như không có. | Identity `service/JwtService.java:133,137,141`; Driver/Trip `security/JwtVerifier.java:91,95,99` |
| F03 | P2 / D | Jackson nhận sai kiểu và tự đổi dữ liệu: role 0/1, password/fullName dạng số được chấp nhận; commandVersion/attempt 1.9 thành 1, tiền/khoảng cách thập phân bị cắt; tọa độ dạng chuỗi được nhận. Sai schema có thể âm thầm đổi command/money. | Identity `web/dto/RegisterRequest.java:16`; Driver `web/dto/ReserveRequest.java:9`; Trip `web/dto/QuoteRequest.java:6` |
| F04 | P2 / D | Catch-all biến lỗi phía client thành 500: UUID/query sai, sai HTTP method/media type; Identity còn trả 500 cho path không tồn tại. | `exception/GlobalExceptionHandler.java` ở cả 5 service; Identity/Trip dòng 77, Driver dòng 122, Matching/Payment dòng 43 |
| F05 | P2 / D | Text có U+0000 qua validation nhưng PostgreSQL không lưu được: đăng ký trả 500 thay vì 400. Đã tái hiện fullName; validation displayName/plate Driver có cùng lỗ hổng ký tự theo code. | Identity `service/UserService.java:56`; Driver `service/DriverService.java` phần validateProfileInput |

**Tái hiện F01:** trên Driver thật và PostgreSQL Testcontainers, cùng body hợp lệ, không X-Internal-Token:

```text
PUT /internal/reservations/{id}       -> 401
PUT /internal;x/reservations/{id}     -> 201, DB driver RESERVED
GET /%69nternal/reservations/{id}      -> 200
```

Nginx hiện không chủ động proxy `/internal`; phạm vi tấn công nội bộ còn phụ thuộc khả năng kết nối trực tiếp backend. Đây vẫn là bỏ qua cơ chế xác thực của service, không phải chỉ sai status. Cần dùng matcher/interceptor/security chain có cùng cách hiểu path với MVC, chặn URL mơ hồ nhất quán và test bằng container HTTP thật. Không chỉ vá riêng ký tự `;` vì encoded path cũng có vấn đề.

F02 tái hiện bằng token ký đúng với key test, khoảng 0,09 giây đầu epoch second: exp=epoch+0,99 bị từ chối, nbf=epoch+0,99 được nhận sớm khoảng 0,9 giây. Cần so sánh NumericDate không cắt phần lẻ hoặc chốt/enforce epoch giây nguyên. Không có bằng chứng bypass do overflow số rất lớn trong lượt này.

F04 có HTTP thực tế: `/api/trips?page=oops`, `size=2147483648`, `/api/trips/not-a-uuid` đều 500; `size=-1` vẫn đúng 400. Phải giữ status 400/404/405/415 thích hợp và bọc ApiError, không đưa mọi lỗi framework vào INTERNAL_ERROR.

### Driver, thời gian và tính đúng đắn dữ liệu

| ID | Ưu tiên / bằng chứng | Phát hiện và ảnh hưởng | Vị trí chính |
|---|---|---|---|
| F06 | P2 / D | Reserve lấy heartbeat cutoff trước khi chờ khóa driver. Heartbeat có thể hết hạn trong lúc chờ nhưng reserve vẫn 201. | Driver `service/ReservationService.java:99`; `repo/ReservationRepository.java:98` |
| F07 | P2 / D | Location/availability lấy lastSeenAt trước UPDATE có thể bị chặn. Update thành công nhưng heartbeat đã stale ngay lúc trả về; thứ tự hoàn tất cũng có thể làm timestamp lùi. | Driver `service/DriverService.java:88,107`; `repo/DriverRepository.java:94,102` |
| F08 | P2 / D | Một reservation bị giữ lock chặn expiry của các driver khác. Worker xử lý tuần tự đầu batch, khóa blocking, không skip/timeout và không cô lập lỗi từng item. | Driver `service/ReservationExpiryWorker.java:23`; `ReservationService.java:205`; repository lock/expiry |
| F09 | P2 / D | Confirm chấp nhận version INT_MAX rồi driver BUSY không còn release/finish hợp lệ: cùng version stale, lớn hơn bị 400; CONFIRMED không expire. | Driver `service/ReservationService.java:155,214,236` |
| F10 | P2 / D | Reserve nhận expiresAt đã qua 60 giây, trả 201 và chiếm driver cho offer không còn hiệu lực; phải chờ worker giải phóng. | Driver `service/ReservationService.java:101,264` |
| F11 | P2 / D | Haversine không clamp a vào [0,1]. Điểm đối cực làm NaN rồi round thành 0: Driver có thể chọn tài xế ở bên kia Trái Đất; Trip tính 0 m và giá cơ bản. | Cả Driver và Trip `geo/Haversine.java:20–24` |
| F12 | P3 / D | expiresAt vượt miền PostgreSQL trả nhầm 409 DRIVER_NOT_AVAILABLE, nói trip/attempt đã tồn tại. Catch DataIntegrityViolationException gom cả lỗi dữ liệu thành unique conflict. | Driver `service/ReservationService.java:106,264` |
| F13 | P3 / S | Availability conflict dùng snapshot trước CAS nên có thể báo AVAILABLE dù DB đã RESERVED; khi RESERVED thông thường còn trả tripId null vì chỉ đọc current_trip_id, không đọc trip từ reservation. | Driver `service/DriverService.java:86–96` |
| F14 | P2 / D | createdAt của create/replay và GET Trip có thể khác nhau: response dùng đồng hồ JVM, row dùng PostgreSQL transaction-start now(). | Trip `service/TripService.java:94`; `repo/TripRepository.java:32`; migration V2 dòng 24 |

F06/F07 đã tái hiện bằng khóa row thật, threshold cấu hình 500 ms và chờ 750 ms: reserve vẫn 201 khi heartbeat hết hạn; updateLocation thành công nhưng candidates ngay sau đó rỗng. Cần lấy thời gian sau khi giành quyền mutation, dùng nguồn thời gian nhất quán; không thay bằng transaction-start now() rồi vẫn giữ cùng lỗi thời gian.

F08: giữ advisory lock của reservation quá hạn đầu tiên, reservation quá hạn thứ hai của driver khác vẫn RESERVED đến khi thả khóa. Các replica cùng scan một head cũng có thể cùng chờ. Nên claim/try-lock không blocking hoặc giới hạn thời gian lock, bỏ qua item đang tranh chấp, xử lý lỗi riêng từng record. Thêm test healthy item sau một item blocked/failed.

F09: reserve version 1 → confirm 2147483647 thành công; finish/release không có version nào vừa lớn hơn version đã lưu vừa trong miền cho phép. Đây là lỗi tiến triển lifecycle ở biên được API chấp nhận. Cần quy định version exhaustion cho state chưa terminal, không chỉ đổi INT sang BIGINT để dời biên.

F11 đã thực thi production class với:

```text
pickup:      (89.84632830981093, 14.397347715373797)
destination: (-89.84632830981093, -165.6026522846262)
thực tế: 0 m; kỳ vọng xấp xỉ 20.015.114 m
```

F12: `expiresAt="+300000-01-01T00:00:00Z"`, trip/attempt mới vẫn bị báo tồn tại. Cần validate miền timestamp và chỉ translate đúng constraint/SQL state của duplicate.

F14 tái hiện bằng service/repository production, transaction PostgreSQL thật, các lần create/get bình thường không chèn delay: create trả `16:47:14Z`, GET trả `16:47:13Z`. Nên dùng một timestamp cho row, response/replay và event khởi tạo, đồng thời test chúng bằng nhau.

### Frontend và vòng đời phiên

| ID | Ưu tiên / bằng chứng | Phát hiện và ảnh hưởng | Vị trí chính |
|---|---|---|---|
| F15 | P2 / D | Request dùng token A nhận 401 muộn sẽ clearSession vô điều kiện, xóa luôn phiên B vừa đăng nhập. | `web/src/api/client.ts:57–58` |
| F16 | P2 / D | Login/register cũ hoàn tất sau khi rời trang có thể ghi đè phiên mới và điều hướng lại. Logout/new auth không vô hiệu hóa continuation cũ. | `web/src/pages/LoginPage.tsx:17–19`; `RegisterPage.tsx:23–27` |
| F17 | P3 / D | Validation frontend/backend còn lệch về trim Unicode, mật khẩu toàn khoảng trắng và email >254 ký tự. | `web/src/auth/validation.ts:24,35–36`; `RegisterPage.tsx:39,65`; `LoginPage.tsx:31` |
| F18 | P3 / S | Register đã 201 nhưng auto-login lỗi vẫn giữ luồng như đăng ký thất bại; submit lại đăng ký sẽ EMAIL_TAKEN. | `web/src/pages/RegisterPage.tsx:23–29` |
| F19 | P3 / S | /me lỗi mạng/5xx bị gộp với chưa xác thực và chuyển về login dù session có thể vẫn hợp lệ; không có trạng thái retry lỗi tạm thời. | `web/src/hooks/useMe.ts:16`; `PassengerHome.tsx:16`; `DriverHome.tsx:16` |

F15 dùng production API/session code với deferred fetch: gửi A, lưu B, resolve 401 của A → B bị xóa. F16 thực thi production LoginPage qua transpile với React/router test doubles: login B hoàn tất trước, login A của component đã rời trang hoàn tất sau → storage B bị đổi lại thành A. Đây là hai điểm commit phiên khác nhau; cần request/session generation và kiểm tra lifetime, không chỉ hủy setState của useMe.

Các ví dụ F17 đã kiểm tra bằng code validation hiện tại, Java trim và validator AntD cài trong repo:

- NBSP ×100 + `ab`: JS trim rồi nhận, Java giữ NBSP thành 102 ký tự và từ chối.
- U+001F ×50 + `ab` + U+001F ×50: frontend từ chối, Java trim thành `ab` và nhận.
- Tám dấu cách ASCII: client password validator/required nhận, backend NotBlank từ chối.
- Email 260 ký tự có cấu trúc được AntD nhận nhưng backend Size(max=254) từ chối.

Không phát hiện mismatch riêng giữa Java/JS cho password bốn emoji: cả hai hiện cùng đếm UTF-16 units. Không đưa trường hợp đó vào số lỗi.

### Logging và gateway

| ID | Ưu tiên / bằng chứng | Phát hiện và ảnh hưởng | Vị trí chính |
|---|---|---|---|
| F20 | P2 / D | Cả 5 layout không thêm newline: nhiều event thành một dòng `}{`, không đọc được như JSON Lines. | Cả 5 `logging/JsonLogLayout.java:58–62`; `logback-spring.xml:8` |
| F21 | P2 / D | log.error(..., exception) vẫn mất stack frame và nested cause: layout chỉ giữ class/message ngoài cùng. Khó chẩn đoán 500 và recovery. | Cả 5 `logging/JsonLogLayout.java:55` |
| F22 | P2 / D | Gateway 502/504 và rate-limit error trả HTML thay vì ApiError JSON. Không phải bản thân status 502 lúc backend tắt là lỗi; lỗi là response contract. | `deploy/nginx/nginx.conf:49,84` |
| F23 | P3 / D | Lỗi Nginx không giữ/tạo correlationId. 413 JSON hiện có correlationId null dù request gửi UUID hợp lệ; 502/503 không có header correlation. | `deploy/nginx/nginx.conf:23,84`; `web/public/errors/payload-too-large.json` |
| F24 | P3 / D | API path không được route rơi vào SPA fallback: `/api/does-not-exist` trả 200 text/html thay vì 404 JSON. | `deploy/nginx/nginx.conf:87` |

F20/F21 đã dùng OutputStreamAppender thật và exception có cause, không chỉ gọi layout từng event riêng. Cần newline ở cả normal/fallback path, giữ stack/cause an toàn trong JSON, không đưa stack vào HTTP response.

Gateway probe hiện tại: `/api/auth/me` khi upstream không chạy → 502 text/html; 16 GET login nhanh → 6×502 và 10×503, lỗi limit là text/html; body 65.537 byte → 413 JSON nhưng không correlation header/id hợp lệ. Chỉ gửi request đọc hoặc request bị gateway chặn, không tạo tài khoản dev.

### Script, dependency và contract

| ID | Ưu tiên / bằng chứng | Phát hiện và ảnh hưởng | Vị trí chính |
|---|---|---|---|
| F25 | P2 / D | generate-dev-keys.sh có CRLF; Linux Bash dừng ở set -euo pipefail với lỗi invalid option. Luồng setup Linux/VM bị chặn. Git Bash trên máy này có thể chạy nên test Windows không phát hiện. | `scripts/generate-dev-keys.sh:5`; thiếu quy tắc LF phù hợp |
| F26 | P2 / D | Chỉ thiếu public key nhưng còn private key: chạy script không --force vẫn tạo private mới, làm mất key cũ/đổi khả năng verify token ngoài dự kiến. | `scripts/generate-dev-keys.sh:20,25` |
| F27 | P2 / D | Mật khẩu DB được nối trực tiếp vào SQL literal. Password hợp lệ có apostrophe làm init PostgreSQL fail, có thể để volume đã khởi tạo nhưng thiếu DB/user. | `infra/postgres/init/01-create-databases.sh:8,13,18,23,28` |
| F28 | P2 / D | User/password RabbitMQ được ghép thẳng vào AMQP URI, không percent-encode. Password có `#` làm parser lỗi thay vì declare topology. | `infra/rabbitmq/declare_topology.es:31` |
| F29 | P2 / D | Script topology source .env như Bash; không tương đương dotenv của Compose. File Compose hợp lệ có password chứa space không quote làm script fail trước khi gọi Docker. | `infra/rabbitmq/declare-topology.sh:16` |
| F30 | P2 / D | Dependency audit có 8 package entries còn advisory, gồm 2 critical/1 high theo npm; cần đánh giá và nâng các cây dependency liên quan. Điều kiện dev/test server phải được phân biệt với bundle production. | `web/package.json:15,22,23`; `web/package-lock.json` |
| F31 | P2 / S | Contract cancel yêu cầu publish event hủy matching, nhưng catalog chỉ có 6 event, không định nghĩa event/routing/payload này; outbox CHECK cũng chỉ 6 loại. Task T20 sẽ không có contract đầy đủ để làm đúng câu yêu cầu đó. | `docs/api-contracts.md:219`; `docs/events.md:53`; Trip migration `V2__trips.sql` bảng outbox |
| F32 | P3 / S | Docker tags còn trôi theo patch/rebuild: RabbitMQ 3-management-alpine, PostgreSQL 17-alpine, Nginx 1.27-alpine. Escript còn copy record từ RabbitMQ 3.13.7, nên clean pull không khóa đúng build đã kiểm chứng. | `deploy/compose.yaml:10,38,63`; `infra/rabbitmq/declare-topology.sh:39`; `declare_topology.es:17` |

F25 chạy byte nguyên bản trong container Linux Bash → exit 2. F26 chạy bản sao riêng ở TEMP, xóa public key rồi chạy không --force → fingerprint private thay đổi; không đụng keys của dự án.

F27 chạy init script trên container PostgreSQL dùng password mẫu có apostrophe → container exit 3, syntax error, không hoàn tất tạo 5 user/DB. Container/volume test đã dọn. F28 chạy amqp_uri:parse trong container disposable, network none, credentials mẫu → error. F29 cùng file dotenv được Compose parse đúng, nhưng topology script exit 127 khi source. Cần escaping/parameterization đúng tại từng lớp, không hạn chế người dùng vào password yếu chỉ để né lỗi parser.

F31 cần chốt một trong các hướng nghiệp vụ: không phát event hủy mà bỏ qua proposal tới trễ dựa trên terminal state, hoặc bổ sung event/schema/binding/consumer rõ ràng. Đây là thiếu sót của contract đã chốt, không phải đòi triển khai trước T20.

## 2. Dependency audit chi tiết

`npm audit --json` chạy thật, exit 1; metadata: **8 package entries: critical 2, high 1, moderate 5**. Các entry có thể cùng bắt nguồn từ một advisory/transitive dependency; không được diễn giải thành 8 exploit độc lập.

| Package cài trong lockfile | Version | Mức từ npm | Loại |
|---|---:|---|---|
| vite | 5.4.21 | high | dev direct |
| vitest | 2.1.9 | critical | dev direct |
| tinypool | 1.1.1 | critical | dev transitive |
| @vitest/mocker | 2.1.9 | moderate | dev transitive |
| esbuild | 0.21.5 | moderate | dev transitive |
| vite-node | 2.1.9 | moderate | dev transitive |
| react-router | 6.30.6 | moderate | runtime transitive |
| react-router-dom | 6.30.6 | moderate | runtime direct |

Đã đối chiếu advisory nguồn: [Vite Windows path bypass](https://github.com/advisories/GHSA-fx2h-pf6j-xcff), [Vitest UI file read/execution](https://github.com/advisories/GHSA-5xrq-8626-4rwp), [Tinypool worker-option gadget](https://github.com/advisories/GHSA-5gmw-xhrv-c9v3), [esbuild dev-server cross-origin access](https://github.com/advisories/GHSA-67mh-4wv8-2f99). Ví dụ Vite Windows issue yêu cầu dev server được expose; Vitest issue phụ thuộc UI server đang lắng nghe. Không có bằng chứng bundle Nginx production hiện bị RCE chỉ vì các dev dependency này. Cần đánh giá đường khai thác/phiên bản sửa và chạy lại test sau cập nhật; lượt audit không tự nâng version.

## 3. Rủi ro/điểm cần chốt thêm, không gộp thành lỗi đã tái hiện đầy đủ

| ID | Mức | Nội dung và trạng thái |
|---|---|---|
| R01 | P3 | Signed zero: cùng principal/key, latitude 0.0 rồi -0.0 trả IDEMPOTENCY_MISMATCH do Double.toString. Đã tái hiện DB thật; cần chốt canonical payload có coi hai tọa độ tương đương này là một hay không trước khi gọi đây là vi phạm contract. Vị trí TripService.java:197; body hash Driver có cùng cách biểu diễn. |
| R02 | P3 | Count và content của list Trip không cùng snapshot; create đồng thời có thể làm envelope không nhất quán. Trace code ở TripService.java:148, chưa tái hiện concurrency riêng; contract chưa cam kết snapshot consistency. |
| R03 | P3 | Login bỏ qua BCrypt khi email không có: 5 cặp local thử cho trung bình known-wrong 85 ms và unknown 12 ms. Có tín hiệu timing enumeration, chưa là nghiên cứu remote có ý nghĩa thống kê; register vốn công khai EMAIL_TAKEN, nên giá trị bảo mật bổ sung hạn chế. UserService.java:78–80. |
| R04 | P2 | Nguồn đồng hồ trộn: JVM ghi/check heartbeat và confirm, DB now() expire. Clock skew giữa DB/replica có thể làm chúng bất đồng hạn hiệu lực. Đã xác nhận nguồn clock qua code, chưa inject clock skew; nên xử lý cùng F06/F07/F14. |
| R05 | P3 | Candidates tải toàn bộ AVAILABLE/fresh rồi tính/sort Java mới limit 5. Chi phí/memory tăng theo tổng tài xế; chưa có benchmark xác nhận ngưỡng gây hỏng. DriverService.java:124–141; DriverRepository.java:109. Cần đánh giá trong T33, cân nhắc bounding/pre-filter khi có số liệu. |

## 4. Khoảng trống kiểm thử và bằng chứng

- **Q01:** JsonLogLayoutTest “không có secret” chỉ kiểm tra thiếu top-level token/password/authorization. Test cố tình đặt secret vào message mà vẫn pass; không chứng minh toàn output không có secret. Lượt này chưa tái hiện plaintext password/JWT/hash leak ở default INFO, nên không khẳng định đang có leak mặc định.
- **Q02:** Test logging gọi layout riêng lẻ, không kiểm tra framing nhiều event qua appender, stack/cause. Đây là lý do F20/F21 không bị phát hiện.
- **Q03:** Security tests trước đây chỉ dùng URL canonical, thiếu matrix/encoded path và assert DB không mutation; thiếu fractional NumericDate/null nbf và sai kiểu JSON.
- **Q04:** Frontend hiện 13 test chỉ nằm ở session/validation helpers. Chưa có test component/hook/auth-race, chưa có browser E2E; các race F15/F16 được thực thi bằng production TS với test doubles, không phải trình duyệt đầy đủ.
- **Q05:** Concurrency/restart hiện chủ yếu ở một service và DB thật. Chưa đủ C01/C02 cấp hệ thống, Matching stop/restart, Payment commit-before-ACK, broker redelivery/DLQ/Saga recovery vì chức năng nền chưa tồn tại.
- **Q06:** Chưa có load k6/raw metrics, full clean deployment CI, quét dependency backend/container bằng công cụ SCA trong lượt này. Không suy từ 130 test pass thành hệ thống phân tán đã nghiệm thu.

## 5. Đủ 24 task còn thiếu theo backlog

T01–T12 được đánh dấu DONE; **T13–T36 đều TODO**. Đây là công việc chưa triển khai theo lộ trình, cần hoàn thành để đạt sản phẩm/tiêu chí môn học; không tính mỗi task là một bug của phần đã làm.

| Task | Phần còn thiếu / hệ quả hiện tại |
|---|---|
| T13 | Outbox publisher Trip, persistent publish/confirm/retry eventId ổn định. Hiện TripRequested chỉ PENDING trong DB. |
| T14 | Consumer inbox/dedup, ACK sau commit, retry hữu hạn và DLQ cho nghiệp vụ. |
| T15 | Matching job thật, ranking/excluded drivers, proposal/NoDriverFound; matching-service hiện skeleton. |
| T16 | Trip consume proposal, durable Saga intent/lease/reconcile và gọi reserve; chưa có assignment đầu cuối. |
| T17 | Public GET driver offer đúng tài xế từ local reservation. |
| T18 | Accept qua Trip và reconcile confirm; T11 internal confirm chưa thay thế endpoint passenger/driver workflow. |
| T19 | Reject, release xác nhận, attempt mới/excluded list và tìm lại. |
| T20 | Cancel, compensation và tranh chấp accept/cancel; còn cần xử lý F31 của contract. |
| T21 | Timeout tổng/offer và phục hồi Saga sau restart, cleanup trước terminal. |
| T22 | Start/complete Trip, finish driver và TripCompleted trong outbox. |
| T23 | Simulated payment consumer, dedup và payment attempt; payment-service hiện skeleton. |
| T24 | Trip consume payment result và cập nhật payment_status độc lập. |
| T25 | Notifications và endpoint lịch sử trip_events có quyền/phân trang. Bảng trip_events hiện có nhưng API chưa có. |
| T26 | Seed/smoke/demo/replay/reset scripts theo phạm vi đã chốt; hiện chỉ có script keys và topology. |
| T27 | UI passenger nghiệp vụ: quote/create/cancel/tracking/payment; hiện placeholder. |
| T28 | UI driver nghiệp vụ: profile/location/offer/accept/reject/start/complete; hiện placeholder. |
| T29 | Dockerfile cho backend, Compose đủ 5 service và màn hình demo. Hiện Compose chỉ có PostgreSQL/RabbitMQ/Nginx; proxy phụ thuộc backend chạy tay trên host/Docker Desktop. |
| T30 | Concurrency hệ thống C01/C02, lặp kiểm tra assignment/intent đúng. |
| T31 | Failure/recovery Matching/Payment, commit-before-ACK và redelivery đúng cửa sổ lỗi. |
| T32 | Bộ security/invalid-input đầu cuối đủ các luồng nghiệp vụ, bổ sung lỗi audit hiện tại. |
| T33 | Performance experiment 10/30/50 concurrent, p50/p95/throughput/error/NO_DRIVER/duplicate-assignment, raw output và cấu hình máy. |
| T34 | CI workflow, clean deploy/rebuild tái lập từ checkout. Không có .github/workflows hiện tại. |
| T35 | Report, architecture/Saga/test-plan/test-results/task-assignment/demo guide và report.pdf. Các file deliverable tương ứng chưa có trong repo. |
| T36 | Gate nghiệm thu cuối/handoff sau khi dependencies hoàn tất. Audit hiện tại không tự làm T36 DONE khi T13–T35 còn thiếu. |

Chưa thể demo đầy đủ đặt xe → matching → accept → complete → payment/notification hoặc failure recovery giữa service. Hiện có 5 module/process skeleton nhưng chỉ Identity/Driver/Trip có phần nghiệp vụ; Matching/Payment chưa có nghiệp vụ hoạt động để chứng minh yêu cầu tối thiểu các business service ở nghiệm thu.

Workspace không có .git hoặc URL repo để kiểm chứng lịch sử đóng góp GitHub. Đây là **thiếu bằng chứng trong workspace được cung cấp**, không phải kết luận rằng nhóm không có GitHub ở nơi khác. Cấu trúc hiện tại cũng chưa có thư mục tests cấp repo và nhiều tài liệu nghiệm thu; chúng thuộc phần bàn giao còn thiếu nêu trên.

## 6. Kiểm tra đã thực sự chạy và giới hạn

| Kiểm tra | Kết quả |
|---|---|
| Root `mvnw.cmd -B clean verify` mới trong lượt audit | BUILD SUCCESS, 130 tests, 0 failures/errors/skipped: Identity 19, Driver 67, Trip 32, Matching 6, Payment 6 |
| Web `npm test -- --run` | 13/13 pass |
| Web `npm run build` | Thành công; cảnh báo chunk >500 kB còn tồn tại, chưa coi riêng là performance defect vì chưa đo |
| `npm audit --json` | Exit 1, 8 package entries như mục 2 |
| `docker compose ... config --quiet` | Thành công |
| Nginx `nginx -t` trong container hiện có | Thành công; syntax pass không phát hiện lỗi contract F22–F24 |
| Probe HTTP/DB/lock/clock/appender/script bổ sung | Các kết quả D ở trên; dùng TEMP/copy, keys và PostgreSQL test riêng |

Bằng chứng chính lưu ngoài source tree:

- `%TEMP%/ds03-full-audit-backend.log`, `ds03-full-audit-web-test.log`, `ds03-full-audit-web-build.log`, `ds03-full-audit-npm-audit.json`.
- `%TEMP%/audit-security-725b35810797470894b750a7956d5796/`: Identity/Driver HTTP + DB, path/JWT/appender probes.
- `%TEMP%/audit-driver-7aa5c178001743838563b845627b637f/`: source test tái hiện và audit-test.log/audit-test2.log (7 expected assertion failures, một control pass).
- `%TEMP%/TripWebAudit-*.java`, `TripPersistenceAudit-*.java`, `TripHttpAudit-*.java`; frontend deferred-request probes thực thi từ stdin.
- Các bản sao TEMP `ds03-audit-keyscript-*`, `ds03-audit-key-rotation-*`, `ds03-audit-dotenv-*`; container test init/URI đã được dọn.

Không chạy browser E2E/screen-reader/load hoặc pentest hạ tầng Internet. Không sửa các lỗi nêu trên trong lượt audit này. Danh sách trình bày toàn bộ phát hiện có cơ sở trong phạm vi đã kiểm tra, giữ riêng các điều kiện/rủi ro chưa được tái hiện thay vì gọi tất cả là lỗi đã chứng minh.

## 7. Thứ tự xử lý

1. F01 và regression bảo vệ path thực tế; sau đó F02/F03/F04/F05.
2. F06–F14 để khép các vấn đề thời gian, tiến triển lifecycle, lỗi dữ liệu/geo và chẩn đoán đúng.
3. F15–F29 cho phiên/UI, logging/gateway và setup/recovery scripts.
4. Triage dependency F30, chốt F31 và khóa build F32; bổ sung Q01–Q06 theo phạm vi tương ứng.
5. Tiếp tục T13/T14 và chuỗi dependency; không thể thay các task nghiệp vụ còn thiếu bằng việc chỉ tăng số unit test.
