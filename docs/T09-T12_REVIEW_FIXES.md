# Sửa R01–R07 của T09/T10/T12 — 20/10/2026

Đã xử lý đủ 7 phát hiện trong [review](T09-T12_REVIEW.md), đồng thời triển khai [T11](tasks/T11.md) theo yêu cầu người dùng. Tài liệu review gốc giữ làm bằng chứng lịch sử; bảng dưới mô tả trạng thái sau sửa.

| ID | Thay đổi | Bằng chứng |
|---|---|---|
| R01 | Idempotency Trip scope theo principal/CREATE_TRIP/key; migration backfill owner từ trip của response cũ. | Hai passenger cùng key/payload tạo trip riêng; khác payload cũng không đụng namespace của nhau; cùng owner vẫn replay/mismatch đúng; concurrent create chỉ một trip/outbox. |
| R02 | Giữ driver_id profile riêng, thêm driver_user_id local cho authorization; candidates và DriverProposed contract mang driverUserId. | Driver có JWT sub khác driverId đọc được đúng trip gán cho mình; người khác và JWT sub trùng profile ID bị loại; role khác không nhận quyền của owner/driver. |
| R03 | Claim processed_commands nguyên tử cùng transaction nghiệp vụ, đợi request thắng rồi replay/conflict. | Hai request trùng chồng lấn đều 201/cùng body, một reservation/processed row; trùng commandId nhưng khác payload trả 201/409 COMMAND_CONFLICT và không giữ driver thứ hai. |
| R04 | Khóa transaction theo authenticated userId trước khi kiểm tra/tạo profile. | Hai POST đồng thời trả 201/200 cùng profile, đúng một driver và một vehicle; plate của người khác vẫn 409. |
| R05 | Hash bind operation + reservationId + canonical body hash. | Đổi URL cùng command/body trả COMMAND_CONFLICT; dùng ID cho loại command khác cũng conflict; retry cùng thao tác giữ response. |
| R06 | Validate version trong miền INT trước claim/ghi; không cast long vượt miền. | 2147483648 trả 400 không giữ driver; 2147483647 được lưu/GET đúng; stale versions vẫn 409. |
| R07 | Tính offset bằng long trước phép nhân và truyền long xuống repository. | Page INT_MAX và trang từng tràn thành offset đầu trả 200/content rỗng, không 500 hoặc đọc nhầm trang. |

## Thay đổi tương thích dữ liệu

- Driver V4 giữ schema cũ, mở rộng tombstone có kiểm soát và chuyển hash RESERVE cũ sang wrapper mới. Test có row processed/reservation từ V3 xác nhận replay status/body cũ được giữ.
- Trip V3 lấy owner của replay cũ qua stored tripId; key cũ của owner vẫn replay response gốc. Không sửa response/hash payload cũ.
- Trip V3 không đoán identity cho trip được gán thủ công từ trước hoặc replay record không truy được trip. Dữ liệu không hợp lệ phải được sửa rõ danh tính trước khi migrate; migration sẽ từ chối thay vì cho quyền sai. Dữ liệu hợp lệ do T12 tạo có driver_id null và được nâng cấp bình thường.
- T16 khi triển khai assignment phải lưu cả driver_id và driver_user_id từ proposal. Chỉ sửa contract và nơi đọc ở lượt này; không code trước consumer/saga/assignment của task tương lai.

## Kiểm tra thực tế

| Lệnh | Kết quả |
|---|---|
| `mvnw.cmd -B clean verify` tại root | BUILD SUCCESS — 130 tests, 0 failures/errors/skipped (Identity 19, Driver 67, Trip 32, Matching 6, Payment 6) |
| `mvnw.cmd -B -pl driver-service verify` | BUILD SUCCESS — 67 tests, 0 failures/errors/skipped |
| `mvnw.cmd -B -pl trip-service verify` | BUILD SUCCESS — 32 tests, 0 failures/errors/skipped |
| `npm test -- --run` tại web | 13/13 pass |
| `npm run build` tại web | Thành công; còn cảnh báo kích thước chunk Vite trên 500 kB, không phải lỗi build |

Các regression quan trọng đã được chạy đỏ trước khi sửa. Trip có thêm test scope role; Driver có thêm test deadline bị vượt khi chờ khóa và defensive finish fencing. Sau sửa, review độc lập Trip và Driver đều chấp nhận.

Test dùng DB Testcontainers riêng. Không dùng bản ghi nghiệp vụ dev, không khởi động service trên port demo 8081–8085. Không có Git metadata trong workspace, không khởi tạo Git hoặc tạo commit giả. Backup nguồn trước sửa ở `%TEMP%/ds03-before-fixes-t11-20261020.zip`.

Log kiểm tra toàn repo: `%TEMP%/ds03-fixes-t11-full-verify.log`; frontend: `%TEMP%/ds03-fixes-t11-web-test.log`, `%TEMP%/ds03-fixes-t11-web-build.log`.
