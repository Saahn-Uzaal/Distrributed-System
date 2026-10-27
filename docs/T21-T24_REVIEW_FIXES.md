# Review và sửa T21–T24 — 27/10/2026

Phạm vi: phục hồi saga, start/complete và finish driver, payment consumer, Trip nhận payment result. Giữ nguyên các thay đổi đã có trong working tree; không commit.

## Lỗi đã tái hiện và sửa

| Phạm vi | Lỗi | Cách sửa và test hồi quy |
|---|---|---|
| T21 — deadline | Release timeout để saga ở WAITING_MATCH; proposal tới muộn có thể đánh NO_DRIVER/ABORTED, khiến worker bỏ mất việc tombstone các reservation còn lại. Proposal sau deadline đến trước worker cũng kết thúc saga mà chưa cleanup. | Lưu COMPENSATING + `context.intent=MATCH_DEADLINE` + danh sách `deadlineTargets` trước HTTP; resume cùng đường compensation. Handler ACK proposal muộn nhưng giữ việc cleanup cho worker. `lateProposalCannotAbandonPendingDeadlineCleanup`, `proposalAfterDeadlineLeavesCleanupForWorker`. |
| T21 — offer expiry | Worker không kiểm tra lại hạn offer hiện tại và không lưu intent trước release; lượt xử lý cũ có thể giải phóng offer mới hoặc để accept chen vào khi release chưa rõ kết quả. | Kiểm tra `offerExpiresAt` bằng đồng hồ DB dưới khóa saga; lưu COMPENSATING/OFFER_EXPIRED trước HTTP; completion kiểm tra đúng attempt/reservation. `staleExpiryDispatchDoesNotReleaseAFreshOffer`, `expiryKeepsDurableIntentUntilReleaseIsKnown`. |
| T21 — batch | Exception trong claim nằm ngoài try/catch từng row, làm dừng cả batch. | Đưa claim vào khối bắt lỗi của từng row. `SagaRecoveryWorkerTest.failedClaimDoesNotPreventRecoveryOfOtherTripsInBatch`. |
| T22 — finish reconcile | GET reservation trả 503 bị bọc thành IllegalStateException, vượt qua catch RestClientException; complete trả 500 dù đã commit COMPLETED và TripCompleted. | Giữ nguyên RestClientResponseException, để finish/reconcile giữ intent pending và trả 200; worker tiếp tục khi driver hồi phục. `completeRemainsSuccessfulWhenFinishAndReconcileReturn503`. |
| T23–T24 — số trong event | `intValue()` có thể biến attempt/schemaVersion 4294967297 thành 1; so sánh bằng double bỏ lọt amount 9223372036854775808 và phần lẻ nhỏ. | Parse số thập phân bằng BigDecimal, kiểm tra nguyên và miền giá trị chính xác trước khi chuyển kiểu. Mỗi service có `InboxMessageValidatorTest`, gồm cả kiểm tra Long.MAX_VALUE vẫn hợp lệ. |
| T23–T24 — JSON null | Payload JSON `null` gây NullPointerException thay vì kết quả malformed; đi nhầm đường DLQ, mất `x-dlq-reason`. | Validator trả `malformed_envelope` cho root null. `nullJsonIsMalformedInsteadOfThrowing` ở cả hai service. |
| T23–T24 — số mũ lớn (rà soát lần 2) | Bản sửa BigDecimal ở trên dùng `toBigIntegerExact()`: số như `1e999999999` (chỉ 11 ký tự, lọt giới hạn độ dài số của Jackson) bị dựng thành BigInteger khổng lồ — đo được `1e10000000` mất 2,5 s, tăng siêu tuyến tính → treo thread consumer/OOM. | Kiểm tra nguyên bằng `stripTrailingZeros().scale() <= 0`, so miền bằng `BigDecimal.compareTo` (thời gian hằng). `hugeExponentsAreRejectedWithoutExpandingTheNumber` (5 case, timeout 2 s) ở cả hai service. |
| T22 — complete sau commit (rà soát lần 2) | Finish trả 4xx thiếu field `code` hoặc 200 thiếu `state` → `IllegalStateException` (không phải RestClientException) thoát khỏi `complete()` → 500 dù COMPLETED + TripCompleted đã commit. | `complete()` bắt RuntimeException của bước finish → coi như UNKNOWN, ghi pending error, trả 200; worker tiếp tục. Stub thêm `codelessErrorCount`; test `completeRemainsSuccessfulWhenFinishErrorHasNoCode`. |

## Bổ sung bằng chứng kiểm tra

- Fault injection T23/T24 ném lỗi **sau khi handler thật đã ghi dữ liệu**, kiểm tra rollback của payments/outbox hoặc payment_status/trip_events cùng inbox.
- Cấu hình fault injection lồng trong test T24 dùng `@TestConfiguration`, tránh bị component scan đưa vào các context test khác.
- Thêm test complete bằng driver không được assign: 409, trip giữ IN_PROGRESS, không tạo TripCompleted hay lệnh finish.
- Test restart REJECT được sửa để đóng context chứa intent rồi phục hồi ở context mới; thêm restart với hai intent OFFER_EXPIRED và MATCH_DEADLINE.
- Stub của test proposal hỗ trợ release/tombstone để kiểm tra đường deadline cleanup qua worker.
- Rà soát độc lập chỉ đọc không phát hiện lỗi chặn trong phạm vi các bản sửa.

## Ghi nhận chưa sửa (rà soát lần 2, không chặn)

- Intent ghi từ request path để `next_retry_at = NULL`, nên worker có thể chạy song song với chính request đó (gửi lặp lệnh cùng commandId). Kết quả vẫn đúng nhờ dedup phía driver và completion idempotent dưới khóa saga; hệ quả phụ: nếu worker hoàn tất reject trước, `TripRequested` mang correlationId ngẫu nhiên thay vì X-Correlation-ID của request. Có thể thêm thời gian ân hạn khi ghi intent nếu cần truy vết chặt.
- Release `OFFER_EXPIRED` bị từ chối (REFUSED) để saga ở COMPENSATING/OFFER_EXPIRED và worker thử lại vô hạn mỗi lease; `retry_count` tăng nhưng chưa có ngưỡng cảnh báo/escalation. Trên luồng hợp lệ không xảy ra (saga WAITING_DRIVER chỉ đi với reservation RESERVED).

## Xác minh

- Trước sửa: 123 test trip có sẵn vượt qua. Test mới tái hiện lỗi: trip 13 failures + 1 error trong 30 test chọn lọc; payment validator 7 failures + 1 error trong 10 test.
- Chạy lại nhóm chọn lọc: **BUILD SUCCESS, 63 tests, 0 failures/errors/skipped** (trip 46, payment 17).
- Lần chạy toàn backend đầu tiên dừng ở test log của identity do tiến trình kế thừa biến môi trường `DEBUG=release`, khiến Spring bật DEBUG và ghi DTO chứa mật khẩu test. Chạy lại trong tiến trình đã bỏ biến DEBUG; không sửa mã identity và không bỏ qua test.
- `./mvnw -B clean verify` toàn backend (đã bỏ DEBUG) sau lượt sửa đầu: **BUILD SUCCESS, 353 tests, 0 failures/errors/skipped** (identity 38, driver 120, trip 141, matching 26, payment 28).
- Rà soát lần 2 (27/10/2026): test validator mới chạy riêng 15/15 ở mỗi service. `./mvnw -B clean verify` toàn backend: **BUILD SUCCESS, 364 tests, 0 failures/errors/skipped** (identity 38, driver 120, trip 147, matching 26, payment 33).

## Giới hạn

Test tích hợp sử dụng PostgreSQL/RabbitMQ thật qua Testcontainers; các test saga/finish dùng HTTP driver stub theo contract. Restart dùng application context mới trong cùng JVM. Chưa chạy E2E qua Nginx hoặc kill JVM thật; phạm vi đó vẫn thuộc T30/T31.
