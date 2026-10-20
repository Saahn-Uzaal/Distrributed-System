# Đánh giá T09, T10 và T12 — 20/10/2026

**Trạng thái sau sửa (20/10/2026): R01–R07 đã được khắc phục, T11 đã hoàn tất.** Full backend clean verify 130/130, web 13/13 + build pass. Chi tiết: [bản sửa và bằng chứng](T09-T12_REVIEW_FIXES.md), [T11](tasks/T11.md). Nội dung đánh giá bên dưới giữ làm lịch sử trước khi sửa.

## Kết luận

Ba task đã có luồng chính và bộ integration test chạy được, nhưng **chưa nên coi là hoàn tất về tính đúng đắn**. Phát hiện **7 vấn đề: 3 P1 và 4 P2**, liên quan đến phạm vi idempotency, định danh driver, request đồng thời và tràn số.

Phạm vi: T09, T10, T12; **bỏ qua T11**. Không đánh giá việc thiếu confirm/release/finish, publisher, saga hay các endpoint thuộc task tương lai là lỗi của ba task này. Chưa sửa mã nguồn triển khai hoặc thay đổi trạng thái backlog.

| Task | Đánh giá |
|---|---|
| T09 — Driver profile/vị trí | Profile, availability CAS, heartbeat và candidates đạt các ca test hiện có; tạo profile chưa idempotent khi request đến đồng thời (R04). |
| T10 — Atomic reserve | CAS ngăn hai reservation chiếm cùng driver; dedup chưa an toàn khi đồng thời, chưa ràng buộc resource trong hash, version có thể bị tràn (R03, R05, R06). |
| T12 — Quote/tạo trip | Fare tính ở server, trip/outbox/history ghi cùng transaction; còn lỗi replay giữa người dùng, định danh assigned driver và phân trang (R01, R02, R07). |

P1: cần ưu tiên xử lý để đáp ứng tính đúng đắn/quyền truy cập của luồng nghiệp vụ. P2: lỗi có điều kiện kích hoạt cụ thể, cần sửa trước khi chốt các tiêu chí liên quan.

## Kiểm tra đã thực sự chạy

- `mvnw.cmd -B -pl driver-service,trip-service verify`: **BUILD SUCCESS; 66 tests, 0 failures, 0 errors, 0 skipped** (Driver 41, Trip 25). Chạy bằng JDK 21; integration tests dùng PostgreSQL 17 qua Testcontainers.
- Tạo ba lớp test chẩn đoán tạm, dùng HTTP thật và DB Testcontainers riêng. Chạy **7 ca; 7 assertion failures, 0 errors, 0 skipped**, xác nhận các hành vi sai bên dưới.
- Hai ca concurrency dùng khóa bảng trong DB test để giữ hai request ở bước INSERT; chỉ thả khóa sau khi `pg_stat_activity` xác nhận cả hai request đã đi qua bước đọc kiểm tra. Vì vậy kết quả không phụ thuộc việc hai thread tình cờ chạy cùng lúc.
- Test assigned driver dùng fixture `trips.driver_id` là driver profile ID, khác identity user ID, đúng cách T09 cấp ID. Đây là kiểm chứng logic đọc/phân quyền đã có ở T12; chưa chạy một luồng assignment T16.
- Các probe chỉ thêm dữ liệu vào DB test; không gọi DB nghiệp vụ/dev. Các lớp Java chẩn đoán và class biên dịch của chúng được dọn khỏi cây build sau review.
- Không chạy lại toàn bộ 5 service, không kiểm tra browser hoặc Nginx trong lượt này.

Lệnh chẩn đoán dùng `-Dmaven.test.failure.ignore=true` để Maven tiếp tục chạy module Trip sau khi các assertion Driver thất bại. **Dòng BUILD SUCCESS của lượt chẩn đoán không có nghĩa các probe pass**; số failure lấy từ báo cáo Surefire XML.

Bằng chứng tạm trên máy hiện tại: `%TEMP%/ds03-review-t09-t12/` chứa ba file `Review*ProbeTest.java`, Surefire XML/TXT và `probes.log`; log verify gốc ở `%TEMP%/ds03-t09-t12-review-verify.log`. Các file trong TEMP không phải tài liệu được quản lý cùng repo.

## R01 — P1 — Replay idempotency không gắn với người tạo trip

**Task:** T12.

**Vị trí:** [TripService.java](../src/trip-service/src/main/java/com/ridehailing/trip/service/TripService.java), dòng 90–112; [IdempotencyKeyRepository.java](../src/trip-service/src/main/java/com/ridehailing/trip/repo/IdempotencyKeyRepository.java); bảng `idempotency_keys` trong migration V2.

Hash chỉ gồm tọa độ. Key là khóa chính toàn cục, không có passenger ID trong khóa, hash hoặc dữ liệu dùng để kiểm tra replay. Nhánh replay trả response đã lưu mà không đối chiếu `user.userId()`.

**Tái hiện:** passenger A tạo trip với key K/payload P; passenger B gửi cùng K/P bằng JWT của B.

**Thực tế:** B nhận **202**, cùng `tripId` của A. Không có trip mới thuộc B. GET detail có kiểm tra owner, nhưng endpoint create/replay đã tiết lộ tripId và thời điểm tạo, đồng thời báo nhầm rằng thao tác tạo trip của B thành công. Điều kiện là B sử dụng lại/biết key K; đây không phải khả năng dò ngẫu nhiên UUID.

**Cần sửa:** ràng buộc replay với principal đã tạo request. Có thể scope key theo passenger, hoặc lưu owner và từ chối dùng lại key của principal khác. Chốt rõ chính sách trong contract; không trả response của A cho B.

**Tiêu chí kiểm tra:** cùng owner/key/payload replay đúng; khác payload trả 409; khác owner không nhận response trip của owner trước, kể cả khi payload giống nhau.

## R02 — P1 — Driver profile ID bị đối chiếu với identity user ID

**Task:** ranh giới T09/T12.

**Vị trí:** [TripService.java](../src/trip-service/src/main/java/com/ridehailing/trip/service/TripService.java), dòng 139–141 và 156–159; [DriverRepository.java](../src/driver-service/src/main/java/com/ridehailing/driver/repo/DriverRepository.java), dòng 76–80; [V2__drivers.sql](../src/driver-service/src/main/resources/db/migration/V2__drivers.sql).

T09 tạo `drivers.id` bằng `gen_random_uuid()` và lưu identity user ID ở `drivers.user_id`. Profile/candidates/reserve đều trả hoặc sử dụng `drivers.id` làm `driverId`. JWT `sub` theo contract là identity user ID. T12 lại truyền `user.userId()` vào điều kiện `driver_id = ?`, và so sánh `row.driverId().equals(user.userId())` khi đọc detail.

**Tái hiện:** fixture có user U, driver profile D với D khác U; đặt `trips.driver_id = D`, gửi GET list/detail bằng JWT DRIVER có sub U.

**Thực tế:** list **200 nhưng totalElements = 0**; detail **404**.

**Ảnh hưởng:** logic đọc đã viết ở T12 không thể nhận diện driver được assign bằng ID do T09/candidates cung cấp. Hiện T12 chưa có assignment thật nên lỗi chưa xuất hiện trong luồng tạo trip; test `driverSeesOnlyAssignedTrips` hiện chỉ kiểm tra danh sách rỗng và không bao phủ trường hợp có assignment.

**Cần sửa:** chốt cách ánh xạ hai loại ID và truyền/lưu đủ identity của assigned driver để kiểm tra caller. Không giả định hai UUID bằng nhau, không đọc trực tiếp DB của Driver từ Trip.

**Tiêu chí kiểm tra:** U đọc được trip gán cho D của mình; driver khác không đọc được; list/count/detail dùng cùng chính sách. Fixture bắt buộc có D khác U.

## R03 — P1 — Hai bản sao cùng reserve command không cùng replay

**Task:** T10.

**Vị trí:** [ReservationService.java](../src/driver-service/src/main/java/com/ridehailing/driver/service/ReservationService.java), bước đọc `findProcessed` đầu method `reserve`, INSERT reservation tại dòng 111–123 và ghi processed command tại dòng 143.

Dedup dùng SELECT trước, ghi `processed_commands` sau cùng. Hai bản sao cùng command đều có thể đọc thấy chưa tồn tại. Bản thứ nhất tạo reservation và commit; bản thứ hai va unique khi INSERT cùng reservation, rồi được map thành `DRIVER_NOT_AVAILABLE`, không đọc lại response command đã commit.

**Tái hiện:** gửi hai PUT đồng thời, cùng reservationId, commandId, version và toàn bộ body.

**Thực tế:** một **201**, một **409 DRIVER_NOT_AVAILABLE**; contract yêu cầu retry cùng command/hash replay response gốc **201**.

CAS vẫn bảo vệ việc driver chỉ bị chiếm một lần. Lỗi ở kết quả idempotency: caller có thể hiểu một thao tác đã thành công là thất bại do driver không còn khả dụng, đặc biệt khi retry sau timeout trong lúc request đầu chưa commit.

**Cần sửa:** serialize/claim theo commandId một cách nguyên tử trong transaction rồi replay hoặc kiểm tra conflict sau khi transaction thắng hoàn tất. Không chỉ bắt thêm unique exception và query tiếp trong transaction PostgreSQL đã bị abort. Có thể tham khảo cách claim `ON CONFLICT DO NOTHING` của T12, kết hợp scope resource ở R05.

**Tiêu chí kiểm tra:** hai request trùng chồng lấn trả 201 với cùng body; DB chỉ một reservation/processed command; commandId trùng nhưng payload khác luôn cho conflict đúng, không 500 và không tạo assignment thứ hai.

## R04 — P2 — Tạo profile đồng thời cho cùng user trả PLATE_TAKEN

**Task:** T09.

**Vị trí:** [DriverService.java](../src/driver-service/src/main/java/com/ridehailing/driver/service/DriverService.java), dòng 58–71.

Cả hai request có thể qua `findByUserId` khi profile chưa tồn tại, rồi cùng INSERT vehicle với plate giống nhau. Request thua bị map thành plate trùng của người khác, dù đây là retry của cùng user.

**Tái hiện:** hai POST profile đồng thời, cùng JWT và body.

**Thực tế:** **201 + 409 PLATE_TAKEN**. Contract quy định lần đầu 201; gọi lại cùng user trả 200 profile đã tạo.

**Cần sửa:** bảo đảm việc tạo theo userId có claim/serialization phù hợp; retry sau cạnh tranh trả profile đã commit. Giữ rollback nguyên tử giữa driver và vehicle. Chỉ trả PLATE_TAKEN khi plate thuộc profile của user khác.

**Tiêu chí kiểm tra:** hai request đồng thời trả 201/200 cùng driverId, đúng một driver/vehicle; user khác dùng cùng plate vẫn 409 và không để vehicle mồ côi.

## R05 — P2 — Hash reserve bỏ qua reservationId trong URL

**Task:** T10.

**Vị trí:** [ReservationService.java](../src/driver-service/src/main/java/com/ridehailing/driver/service/ReservationService.java), lời gọi `requestHash(cmd)` và method `requestHash`.

Hash chỉ nhận body; `reservationId` là path parameter nên không được ràng buộc với processed command. Dedup chạy trước kiểm tra reservation đích.

**Tái hiện:** reserve thành công tại `/internal/reservations/A`; giữ nguyên commandId/body, đổi URL sang `/internal/reservations/B`.

**Thực tế:** request tới B nhận **201 với reservationId A**; B không được tạo. Nhánh replay còn bỏ qua việc B có thể đã terminal. Chưa có assignment B bị tạo sai trong probe này; vấn đề là trả thành công cho resource khác và bỏ qua guard của resource đích.

**Cần sửa:** bind command với reservationId và loại thao tác, bằng canonical hash hoặc metadata được kiểm tra trước replay. Cập nhật định nghĩa canonical request trong contract vì định nghĩa hiện tại chỉ nói đến các trường command body.

**Tiêu chí kiểm tra:** retry cùng URL/body replay; cùng commandId chuyển sang URL khác trả 409 COMMAND_CONFLICT, không replay thành công của A.

## R06 — P2 — commandVersion long bị cast sang int và lưu số âm

**Task:** T10.

**Vị trí:** [ReservationService.java](../src/driver-service/src/main/java/com/ridehailing/driver/service/ReservationService.java), dòng 97–113; DTO dùng `long` nhưng repository/schema dùng `int`/`INT`.

Chỉ kiểm tra version nhỏ hơn 1; sau đó cast `(int) cmd.commandVersion()` khi lưu. Response vẫn chứa giá trị long ban đầu.

**Tái hiện:** reserve với `commandVersion = 2147483648`.

**Thực tế:** **201**, response version **2147483648**, nhưng `last_command_version` trong DB là **-2147483648**. PUT và GET/version guard vì thế nhìn thấy các version khác nhau; mất tính đơn điệu của version.

**Cần sửa:** thống nhất độ rộng kiểu dữ liệu. Nếu dùng INT, từ chối giá trị vượt `Integer.MAX_VALUE` bằng 400 trước mọi ghi DB; nếu dùng long, đổi xuyên suốt DB/repository/view sang BIGINT/long bằng migration phù hợp.

**Tiêu chí kiểm tra:** biên min/max và max+1; request không hợp lệ không giữ driver; mọi version được chấp nhận phải được lưu và đọc lại đúng giá trị.

## R07 — P2 — Phân trang tràn int tạo OFFSET âm và trả 500

**Task:** T12.

**Vị trí:** [TripService.java](../src/trip-service/src/main/java/com/ridehailing/trip/service/TripService.java), dòng 134–141; [TripRepository.java](../src/trip-service/src/main/java/com/ridehailing/trip/repo/TripRepository.java), tham số `int offset`.

`page` chỉ được kiểm tra không âm. Phép nhân `page * effectiveSize` chạy bằng int; có thể tràn sang số âm trước khi truyền vào PostgreSQL.

**Tái hiện:** JWT hợp lệ gọi `GET /api/trips?page=2147483647&size=100`.

**Thực tế:** **500 INTERNAL_ERROR**, nguyên nhân OFFSET âm. Với các giá trị tràn thành số dương, cũng có nguy cơ đọc nhầm trang.

**Cần sửa:** tính offset bằng long từ trước phép nhân và dùng kiểu phù hợp trong repository; hoặc giới hạn page rõ trong contract và trả 400. Không để input phân trang hợp lệ theo contract hiện tại trở thành lỗi server.

**Tiêu chí kiểm tra:** page thường, page ngoài phạm vi và page rất lớn; trả envelope rỗng hoặc 400 theo chính sách đã chốt, không 500/nhầm trang.

## Thứ tự xử lý đề xuất

R01 và R02 trước để chốt phạm vi principal/định danh; R03 cùng R05 để sửa dedup reserve nhất quán; sau đó R04, R06, R07. Bổ sung các test tái hiện vào bộ test chính khi sửa, rồi chạy lại verify của hai service. Các task tương lai vẫn triển khai theo dependency; báo cáo này không yêu cầu triển khai T11.
