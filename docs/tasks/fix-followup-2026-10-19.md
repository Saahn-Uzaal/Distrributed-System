# Sửa bổ sung sau review lần hai — F01, F04, F07

Ngày: **19/10/2026**. Trạng thái: **DONE**.

Nguồn việc: người dùng yêu cầu sửa ngay các điểm còn sót sau lần đánh giá lại T01–T06. Đây là phần tiếp nối [đợt sửa đầu](fix-2026-10-19.md); các số liệu 12 test trong nhật ký cũ là kết quả của đợt đó, không phải số test hiện tại.

## Thay đổi

### F01 — JWT

- `src/identity-service/src/main/java/com/ridehailing/identity/service/JwtService.java`: kiểm tra payload chuyển thành JSON object thành công và khác null; payload không hợp lệ được chuyển thành `UnauthorizedException`, filter trả `401 UNAUTHORIZED` thay vì 500.
- Audience phải là mảng chứa toàn string. Phần tử là số hoặc null bị từ chối, không bị lọc bỏ để chấp nhận phần còn lại.
- Đổi điều kiện hết hạn thành `exp <= now`, đúng với clock skew 0 giây. Token không còn hợp lệ ngay tại giây hết hạn.
- Cập nhật chính sách tương ứng ở `docs/api-contracts.md` mục 2.

### F04 — fullName sau trim

- `src/identity-service/src/main/java/com/ridehailing/identity/web/dto/RegisterRequest.java`: giữ `@NotBlank` cho fullName, bỏ kiểm tra độ dài trên chuỗi thô. `UserService` tiếp tục trim rồi kiểm tra 2–100 ký tự trước khi lưu.
- Tên đúng 100 ký tự sau trim được chấp nhận và lưu đúng giá trị đã trim, kể cả khi chuỗi đầu vào có thêm khoảng trắng ở hai đầu. Tên 101 ký tự sau trim vẫn trả 400.

### F07 — Java trong terminal

- `README.md`: thêm lệnh đặt cả `JAVA_HOME` và `PATH` cho PowerShell và Git Bash, kèm `java -version` để xác nhận Java 21.
- Sửa ví dụ nhập DB password dùng `read -s` thay cho placeholder chưa quote có thể bị shell hiểu là toán tử chuyển hướng.
- Không đổi PATH hệ thống hoặc cài lại Java; các lệnh chỉ áp dụng cho terminal hiện tại.

## Regression tests và kiểm tra đã chạy

Thêm 4 test HTTP/PostgreSQL thật trong `src/identity-service/src/test/java/com/ridehailing/identity/AuthFlowIntegrationTest.java`:

| Test | Hành vi được kiểm tra |
|---|---|
| `signedPayloadMustBeAJsonObject` | Payload ký đúng nhưng là null, text không phải JSON, JSON scalar hoặc array đều trả 401. |
| `audienceMustContainOnlyStrings` | Mảng audience có service hợp lệ nhưng kèm số/null vẫn bị từ chối. |
| `expirationAtCurrentSecondIsAlreadyExpired` | Token có exp bằng giây xử lý request bị từ chối; chỉ kết luận khi request hoàn tất trong cùng giây để tránh che lỗi tại ranh giới giây. |
| `fullNameMaximumLengthAppliesAfterTrimming` | Tên có khoảng trắng hai đầu, sau trim đúng 100 ký tự, được tạo và lưu đúng; vượt 100 sau trim trả 400. |

### Trước khi sửa code

Chạy tại project root với JDK 21 và Docker đang hoạt động:

```powershell
.\mvnw.cmd -B -pl identity-service '-Dtest=AuthFlowIntegrationTest#signedPayloadMustBeAJsonObject+audienceMustContainOnlyStrings+expirationAtCurrentSecondIsAlreadyExpired+fullNameMaximumLengthAppliesAfterTrimming' test
```

Kết quả: **4 tests, 4 failures, 0 errors, 0 skipped**. Lỗi assertion lần lượt xác nhận các HTTP status sai: 500 thay vì 401; 200 thay vì 401 cho audience; 200 thay vì 401 tại thời điểm hết hạn; 400 thay vì 201 cho tên hợp lệ sau trim. Không phải lỗi compile hoặc môi trường.

### Sau khi sửa code

```powershell
$env:JAVA_HOME = 'C:\Users\Hung Nguyen\dev-tools\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -B clean verify
```

Kết quả: **BUILD SUCCESS** cho root và cả 5 service; Identity **16 tests, 0 failures, 0 errors, 0 skipped**. Các service còn lại chưa có test, không tính chúng thành test đã pass.

- Kiểm tra cấu hình Java theo README trên cả PowerShell và Git Bash: `java -version` đều trả **Temurin 21.0.12.1**.
- Chọn `java.exe` từ PATH sau khi cấu hình, chạy jar Identity với env DB của service trên port thử 18098: `/actuator/health` trả **200, UP**.
- Dừng tiến trình smoke sau kiểm tra. PostgreSQL/RabbitMQ hiện có vẫn healthy; không xóa queue, volume hoặc dữ liệu hiện có.

## Phạm vi và giới hạn

- Chỉ sửa các điểm còn sót của F01/F04/F07; không triển khai T07/T08 hoặc business handler.
- Không chạy lại routing RabbitMQ và restart/persistence trong đợt sửa bổ sung này: không có thay đổi topology/Compose. Kết quả routing đã được xác nhận ở lần review lại và đợt sửa trước.
- Các nhánh Saga trong F06 vẫn là contract; chưa có runtime Saga để kiểm thử.
- Workspace chưa có `.git`: không tạo commit và không có Git diff/tracked-secret audit. Không thay đổi lịch sử hoặc số liệu của lần review ban đầu.
