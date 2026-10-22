import { describe, expect, it } from 'vitest'
import {
  MAX_EMAIL_LENGTH,
  MAX_PASSWORD_BYTES,
  javaTrim,
  utf8ByteLength,
  validateEmail,
  validateFullName,
  validateLoginPassword,
  validatePassword
} from './validation'

describe('utf8ByteLength', () => {
  it('ASCII: số byte = số ký tự', () => {
    expect(utf8ByteLength('abc')).toBe(3)
    expect(utf8ByteLength('a'.repeat(MAX_PASSWORD_BYTES))).toBe(MAX_PASSWORD_BYTES)
  })

  it('tiếng Việt: ạ (U+1AB1) = 3 byte/ký tự', () => {
    expect(utf8ByteLength('ạ')).toBe(3)
    // 'Nguyễn Văn A': 9 ASCII + ỳ (U+1EF5, 3B) + á (U+00E1, 2B) = 14 byte
    expect(utf8ByteLength('Nguyễn Văn A')).toBe('Nguyễn Văn A'.length + 3)
  })

  it('chữ ngoài BMP (emoji U+1F600) = 4 byte/ký tự', () => {
    expect(utf8ByteLength('😀')).toBe(4)
  })
})

describe('validatePassword — ranh giới theo BYTE UTF-8, không theo ký tự', () => {
  it('72 ký tự ASCII (72 byte) hợp lệ; 73 ký tự bị từ chối', () => {
    expect(validatePassword('a'.repeat(72))).toBeNull()
    expect(validatePassword('a'.repeat(73))).not.toBeNull()
  })

  it('tiếng Việt: 24×ạ = 72 byte hợp lệ; 25×ạ = 75 byte bị từ chối', () => {
    // 25 ký tự vẫn < 72 KÝ TỰ — kiểm tra naive theo độ dài chuỗi sẽ sai.
    expect(validatePassword('ạ'.repeat(24))).toBeNull()
    expect(validatePassword('ạ'.repeat(25))).not.toBeNull()
  })

  it('trộn ASCII + tiếng Việt đúng ranh giới byte', () => {
    const mixed = 'a'.repeat(48) + 'ạ'.repeat(8) // 48 + 24 = 72 byte
    expect(validatePassword(mixed)).toBeNull()
    expect(validatePassword(mixed + 'ạ')).not.toBeNull() // 75 byte
  })

  it('dưới 8 ký tự bị từ chối', () => {
    expect(validatePassword('abc123')).not.toBeNull()
    expect(validatePassword('a'.repeat(8))).toBeNull()
  })
})

describe('javaTrim — khớp EXACT semantics Java String.trim() (F17)', () => {
  it('bỏ ký tự <= U+0020 hai đầu; NBSP (U+00A0) KHÔNG bị bỏ (khác JS trim())', () => {
    expect(javaTrim('\u00A0a\u00A0')).toBe('\u00A0a\u00A0') // Java giữ NBSP
    expect(javaTrim('  a  ')).toBe('a')
  })

  it('U+001F (control char, <= U+0020) bị bỏ — JS trim() cũng bỏ nhưng vì lý do khác', () => {
    expect(javaTrim('\u001Fa\u001F')).toBe('a')
  })
})

describe('validatePassword — F17: không toàn whitespace (Java @NotBlank)', () => {
  it('password chỉ gồm khoảng trắng bị từ chối dù đủ 8 ký tự', () => {
    expect(validatePassword(' '.repeat(12))).not.toBeNull()
    expect(validatePassword('\t\n ')).not.toBeNull()
  })

  it('password có NBSP KHÔNG coi là blank (Java @NotBlank theo trim <= U+0020)', () => {
    // '\u00A0'.repeat(8): Java trim() giữ NBSP -> không blank; đủ 8 ký tự, 8 byte.
    expect(validatePassword('\u00A0'.repeat(8))).toBeNull()
  })
})

describe('validateLoginPassword — F17: login chỉ enforce @NotBlank (không min length)', () => {
  it('blank bị từ chối; chuỗi ngắn hợp lệ', () => {
    expect(validateLoginPassword('   ')).not.toBeNull()
    expect(validateLoginPassword('x')).toBeNull()
  })
})

describe('validateEmail — F17: không blank + độ dài thô <= 254 (backend @Size)', () => {
  it('blank bị từ chối', () => {
    expect(validateEmail('   ')).not.toBeNull()
  })

  it(`đúng ${MAX_EMAIL_LENGTH} ký tự hợp lệ; ${MAX_EMAIL_LENGTH + 1} ký tự bị từ chối`, () => {
    const ok = `a@${'b'.repeat(MAX_EMAIL_LENGTH - 6)}.com` // 'a@'(2) + N + '.com'(4) = 254
    expect(ok.length).toBe(MAX_EMAIL_LENGTH)
    expect(validateEmail(ok)).toBeNull()
    expect(validateEmail(ok + 'x')).not.toBeNull()
  })
})

describe('validateFullName — kiểm tra sau trim', () => {
  it('tên 98 ký tự + 2 khoảng trắng hai đầu (raw 100) hợp lệ sau trim', () => {
    // Trường hợp review R06: client cũ chặn theo chuỗi thô dù backend chấp nhận sau trim.
    expect(validateFullName('a'.repeat(98) + '  ')).toBeNull()
  })

  it('trim còn > 100 ký tự bị từ chối; ranh giới raw 102 (trim -> 100) hợp lệ', () => {
    expect(validateFullName('a'.repeat(101))).not.toBeNull()
    expect(validateFullName('a'.repeat(100) + '  ')).toBeNull() // trim -> đúng 100
  })

  it('trim còn < 2 ký tự bị từ chối (vd " a")', () => {
    expect(validateFullName(' a')).not.toBeNull()
    expect(validateFullName('   ')).not.toBeNull()
  })

  it('tên tiếng Việt thông thường hợp lệ', () => {
    expect(validateFullName('Nguyễn Văn A')).toBeNull()
  })

  it('F17: NBSP hai đầu được GIỮ (Java trim không bỏ) -> tên vẫn hợp lệ', () => {
    // JS String.trim() sẽ bỏ NBSP và cho ra 'Nguyễn' — frontend phải khớp backend.
    expect(javaTrim('\u00A0Nguyễn Văn A\u00A0')).toBe('\u00A0Nguyễn Văn A\u00A0')
    expect(validateFullName('\u00A0Nguyễn Văn A\u00A0')).toBeNull()
  })

  it('F17: U+001F hai đầu bị bỏ (Java trim) -> " a" dạng control char bị từ chối', () => {
    expect(validateFullName('\u001FA\u001F')).not.toBeNull() // sau javaTrim = 'A' (1 ký tự)
  })
})
