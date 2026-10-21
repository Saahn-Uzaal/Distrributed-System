import { describe, expect, it } from 'vitest'
import { newUuid, uuidV4Fallback } from './client'

const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

describe('newUuid (N16 — UUID correlation ID ngoài secure context)', () => {
  it('secure context: trả về UUID v4 hợp lệ', () => {
    expect(newUuid()).toMatch(UUID_V4_RE)
  })

  it('fallback (getRandomValues): UUID v4 hợp lệ và duy nhất', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 1000; i++) {
      const u = uuidV4Fallback()
      expect(u).toMatch(UUID_V4_RE)
      expect(seen.has(u)).toBe(false)
      seen.add(u)
    }
  })

  it('newUuid tự dùng fallback khi crypto.randomUUID không tồn tại (HTTP thường)', () => {
    const originalCrypto = globalThis.crypto
    try {
      // Mô phỏng non-secure context: crypto chỉ có getRandomValues, không có randomUUID.
      Object.defineProperty(globalThis, 'crypto', {
        configurable: true,
        value: { getRandomValues: (arr: Uint8Array) => originalCrypto.getRandomValues(arr) }
      })
      expect(newUuid()).toMatch(UUID_V4_RE)
    } finally {
      Object.defineProperty(globalThis, 'crypto', { configurable: true, value: originalCrypto })
    }
  })
})
