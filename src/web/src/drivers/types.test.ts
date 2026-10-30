import { describe, expect, it } from 'vitest'
import { driverStatusLabel, offerRemainingMs, validLatitude, validLongitude } from './types'

describe('driverStatusLabel', () => {
  it('mọi driver state có nhãn tiếng Việt không rỗng', () => {
    for (const s of ['OFFLINE', 'AVAILABLE', 'RESERVED', 'BUSY'] as const) {
      expect(driverStatusLabel(s).length).toBeGreaterThan(0)
    }
  })
})

describe('offerRemainingMs', () => {
  it('dương khi offer còn hạn, <=0 khi hết hạn', () => {
    const now = Date.parse('2026-10-29T10:00:00Z')
    expect(offerRemainingMs('2026-10-29T10:00:35Z', now)).toBe(35_000)
    expect(offerRemainingMs('2026-10-29T09:59:59Z', now)).toBe(-1000)
  })

  it('ISO không parse được → 0 (coi như hết hạn)', () => {
    expect(offerRemainingMs('not-a-date', Date.now())).toBe(0)
  })
})

describe('validLatitude / validLongitude', () => {
  it('nhận trong khoảng, từ chối ngoài khoảng và null', () => {
    expect(validLatitude(21.03)).toBe(true)
    expect(validLatitude(90)).toBe(true)
    expect(validLatitude(90.1)).toBe(false)
    expect(validLatitude(null)).toBe(false)
    expect(validLongitude(-180)).toBe(true)
    expect(validLongitude(180.5)).toBe(false)
    expect(validLongitude(Number.NaN)).toBe(false)
  })
})
