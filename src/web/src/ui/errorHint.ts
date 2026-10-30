/**
 * T27 — gợi ý tiếng Việt cho các mã lỗi đã biết (contract §1.2),
 * hiển thị bên dưới message server để "lỗi hiển thị rõ" (acceptance T27).
 */
/** Lỗi không cho biết server đã xử lý hay chưa: không được coi là thất bại, phải đọc lại hoặc thử lại idempotent. */
const AMBIGUOUS_CODES = new Set(['REQUEST_TIMEOUT', 'NETWORK_ERROR', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR', 'UNKNOWN'])

export function isAmbiguousOutcome(code: string): boolean {
  return AMBIGUOUS_CODES.has(code)
}

export function errorHint(code: string): string | null {
  switch (code) {
    case 'ACTIVE_TRIP_EXISTS':
      return 'Bạn đã có một chuyến chưa kết thúc. Hủy hoặc chờ chuyến đó xong trước khi đặt chuyến mới.'
    case 'IDEMPOTENCY_MISMATCH':
      return 'Yêu cầu dùng lại Idempotency-Key nhưng nội dung khác — hệ thống không tạo trip mới. Thử lại với dữ liệu hiện tại.'
    case 'STATE_CONFLICT':
      return 'Trạng thái đã thay đổi so với lúc bấm. Màn hình sẽ tự cập nhật — kiểm tra lại trước khi thao tác tiếp.'
    case 'NOT_OFFERED_DRIVER':
      return 'Bạn không phải tài xế được đề nghị cho chuyến này (hoặc offer đã hết hạn).'
    case 'RATE_LIMITED':
      return 'Quá nhiều yêu cầu trong thời gian ngắn. Đợi vài giây rồi thử lại.'
    case 'REQUEST_TIMEOUT':
      return 'Yêu cầu quá chậm và kết quả phía server chưa rõ. Bấm lại sẽ dùng cùng Idempotency-Key — không tạo trip trùng.'
    case 'UPSTREAM_UNAVAILABLE':
    case 'INTERNAL_ERROR':
    case 'UNKNOWN':
      return 'Máy chủ gặp lỗi và kết quả chưa rõ. Bấm lại sẽ dùng cùng Idempotency-Key — không tạo trip trùng.'
    case 'NETWORK_ERROR':
      return 'Không kết nối được máy chủ. Kiểm tra mạng rồi bấm lại — cùng Idempotency-Key, không tạo trip trùng.'
    default:
      return null
  }
}
