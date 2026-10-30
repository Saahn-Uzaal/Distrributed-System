import { describe, expect, it } from 'vitest'
import { errorHint, isAmbiguousOutcome } from './errorHint'

describe('errorHint (acceptance T27 "lỗi hiển thị rõ")', () => {
  it('mọi mã lỗi nghiệp vụ đã biết có gợi ý tiếng Việt', () => {
    const codes = [
      'ACTIVE_TRIP_EXISTS',
      'IDEMPOTENCY_MISMATCH',
      'STATE_CONFLICT',
      'NOT_OFFERED_DRIVER',
      'RATE_LIMITED',
      'REQUEST_TIMEOUT',
      'NETWORK_ERROR'
    ]
    for (const code of codes) {
      const hint = errorHint(code)
      expect(hint, `hint cho ${code}`).not.toBeNull()
      expect((hint as string).length).toBeGreaterThan(0)
    }
  })

  it('lỗi chưa rõ kết quả phân biệt với lỗi nghiệp vụ', () => {
    for (const code of ['REQUEST_TIMEOUT', 'NETWORK_ERROR', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR', 'UNKNOWN']) {
      expect(isAmbiguousOutcome(code), code).toBe(true)
      expect(errorHint(code), code).not.toBeNull()
    }
    for (const code of ['ACTIVE_TRIP_EXISTS', 'STATE_CONFLICT', 'NOT_OFFERED_DRIVER', 'INVALID_INPUT']) {
      expect(isAmbiguousOutcome(code), code).toBe(false)
    }
  })

  it('mã lạ → null (UI chỉ hiện message server)', () => {
    expect(errorHint('SOMETHING_ELSE')).toBeNull()
  })
})
