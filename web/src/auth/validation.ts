/**
 * T08 (R06 fix) — validation form khớp contract api-contracts.md §3.1 (F04):
 * - password: tối thiểu 8 ký tự và **tối đa 72 byte UTF-8** (giới hạn đầu vào BCrypt).
 *   Kiểm tra theo BYTE, không theo số ký tự — ví dụ 'ạ' (U+1AB1) = 3 byte.
 * - fullName: 2–100 ký tự **sau khi trim** khoảng trắng hai đầu (như backend).
 */

/** Số byte UTF-8 của chuỗi (pure function — test được mà không cần TextEncoder). */
export function utf8ByteLength(value: string): number {
  let bytes = 0
  for (const ch of value) {
    const code = ch.codePointAt(0)!
    if (code <= 0x7f) bytes += 1
    else if (code <= 0x7ff) bytes += 2
    else if (code <= 0xffff) bytes += 3
    else bytes += 4
  }
  return bytes
}

export const MAX_PASSWORD_BYTES = 72

/** Trả về message lỗi, hoặc null nếu hợp lệ. */
export function validatePassword(value: string): string | null {
  if (value.length < 8) {
    return 'Mật khẩu phải có ít nhất 8 ký tự.'
  }
  if (utf8ByteLength(value) > MAX_PASSWORD_BYTES) {
    return `Mật khẩu tối đa ${MAX_PASSWORD_BYTES} byte UTF-8.`
  }
  return null
}

/** Trả về message lỗi, hoặc null nếu hợp lệ. Kiểm tra trên giá trị đã trim. */
export function validateFullName(value: string): string | null {
  const trimmed = value.trim()
  if (trimmed.length < 2 || trimmed.length > 100) {
    return 'Họ tên phải có 2–100 ký tự sau khi bỏ khoảng trắng hai đầu.'
  }
  return null
}
