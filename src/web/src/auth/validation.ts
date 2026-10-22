/**
 * T08 (R06 fix) — validation form khớp contract api-contracts.md §3.1 (F04):
 * - password: tối thiểu 8 ký tự và **tối đa 72 byte UTF-8** (giới hạn đầu vào BCrypt).
 *   Kiểm tra theo BYTE, không theo số ký tự — ví dụ 'ạ' (U+1AB1) = 3 byte.
 * - fullName: 2–100 ký tự **sau khi trim** khoảng trắng hai đầu (như backend).
 *
 * F17 fix (lệch trim JS/Java): Java `String.trim()` chỉ bỏ ký tự <= U+0020, còn JS
 * `trim()` bỏ thêm NBSP (U+00A0)... và ngược lại với U+001F. Frontend dùng `javaTrim`
 * để khớp EXACT semantics backend:
 * - password toàn khoảng trắng -> 400 @NotBlank phía backend;
 * - email > 254 ký tự -> 400 @Size(max=254) phía backend (AntD không chặn).
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

/** Trim theo semantics của Java `String.trim()`: bỏ ký tự codePoint <= U+0020 hai đầu. */
export function javaTrim(value: string): string {
  let start = 0
  let end = value.length
  while (start < end && value.charCodeAt(start) <= 0x20) start++
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end--
  return value.slice(start, end)
}

export const MAX_PASSWORD_BYTES = 72

/** Email tối đa 254 ký tự phía backend (@Size(max=254), áp trên chuỗi thô). */
export const MAX_EMAIL_LENGTH = 254

/** Trả về message lỗi, hoặc null nếu hợp lệ. Khớp register phía backend:
 * @NotBlank (không toàn whitespace theo Java) + @Size(min=8) + ≤ 72 byte UTF-8. */
export function validatePassword(value: string): string | null {
  if (javaTrim(value).length === 0) {
    return 'Mật khẩu không được chỉ gồm khoảng trắng.'
  }
  if (value.length < 8) {
    return 'Mật khẩu phải có ít nhất 8 ký tự.'
  }
  if (utf8ByteLength(value) > MAX_PASSWORD_BYTES) {
    return `Mật khẩu tối đa ${MAX_PASSWORD_BYTES} byte UTF-8.`
  }
  return null
}

/** Login phía backend chỉ enforce @NotBlank password (sai credentials -> 401, không 400). */
export function validateLoginPassword(value: string): string | null {
  if (javaTrim(value).length === 0) {
    return 'Mật khẩu không được chỉ gồm khoảng trắng.'
  }
  return null
}

/** Email phía backend: @NotBlank + @Email + @Size(max=254) trên chuỗi thô.
 * Định dạng email do AntD `type: 'email'` kiểm tra; đây chặn độ dài và blank. */
export function validateEmail(value: string): string | null {
  if (javaTrim(value).length === 0) {
    return 'Email không được để trống.'
  }
  if (value.length > MAX_EMAIL_LENGTH) {
    return `Email tối đa ${MAX_EMAIL_LENGTH} ký tự.`
  }
  return null
}

/** Trả về message lỗi, hoặc null nếu hợp lệ. Kiểm tra trên giá trị đã trim (Java semantics). */
export function validateFullName(value: string): string | null {
  const trimmed = javaTrim(value)
  if (trimmed.length < 2 || trimmed.length > 100) {
    return 'Họ tên phải có 2–100 ký tự sau khi bỏ khoảng trắng hai đầu.'
  }
  return null
}
