import { describe, expect, it } from 'vitest'
import {
  canCancelTrip,
  canCompleteTrip,
  canStartTrip,
  formatDateTime,
  formatVnd,
  isTerminalTrip,
  isTripSettled,
  paymentStatusLabel,
  tripStateColor,
  tripStateLabel
} from './types'

describe('isTripSettled', () => {
  it('COMPLETED còn chờ payment thì chưa xong', () => {
    expect(isTripSettled({ state: 'COMPLETED', paymentStatus: 'PENDING' })).toBe(false)
    expect(isTripSettled({ state: 'COMPLETED', paymentStatus: 'SUCCEEDED' })).toBe(true)
    expect(isTripSettled({ state: 'COMPLETED', paymentStatus: 'FAILED' })).toBe(true)
  })

  it('trip chưa terminal chưa xong; hủy/không có tài xế là xong', () => {
    expect(isTripSettled({ state: 'IN_PROGRESS', paymentStatus: 'NOT_REQUESTED' })).toBe(false)
    expect(isTripSettled({ state: 'CANCELLED', paymentStatus: 'NOT_REQUESTED' })).toBe(true)
    expect(isTripSettled({ state: 'NO_DRIVER', paymentStatus: 'NOT_REQUESTED' })).toBe(true)
  })
})

describe('isTerminalTrip', () => {
  it('COMPLETED/CANCELLED/NO_DRIVER là terminal (polling dừng tại đây)', () => {
    expect(isTerminalTrip('COMPLETED')).toBe(true)
    expect(isTerminalTrip('CANCELLED')).toBe(true)
    expect(isTerminalTrip('NO_DRIVER')).toBe(true)
  })

  it('các state còn lại không terminal (kể cả CANCELLING transient)', () => {
    for (const s of ['SEARCHING', 'DRIVER_OFFERED', 'CANCELLING', 'ACCEPTED', 'IN_PROGRESS'] as const) {
      expect(isTerminalTrip(s)).toBe(false)
    }
  })
})

describe('canCancelTrip (chốt T20, contract §3.3)', () => {
  it('SEARCHING/DRIVER_OFFERED/CANCELLING được hủy', () => {
    expect(canCancelTrip('SEARCHING')).toBe(true)
    expect(canCancelTrip('DRIVER_OFFERED')).toBe(true)
    expect(canCancelTrip('CANCELLING')).toBe(true)
  })

  it('từ ACCEPTED trở đi không hủy được (server trả 409 STATE_CONFLICT)', () => {
    for (const s of ['ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_DRIVER'] as const) {
      expect(canCancelTrip(s)).toBe(false)
    }
  })
})

describe('canStartTrip/canCompleteTrip (T28, contract §3.3)', () => {
  it('start chỉ từ ACCEPTED; complete chỉ từ IN_PROGRESS', () => {
    expect(canStartTrip('ACCEPTED')).toBe(true)
    expect(canStartTrip('IN_PROGRESS')).toBe(false)
    expect(canCompleteTrip('IN_PROGRESS')).toBe(true)
    expect(canCompleteTrip('COMPLETED')).toBe(false)
  })
})

describe('labels', () => {
  it('mọi trip state có nhãn tiếng Việt không rỗng + màu hợp lệ', () => {
    const states = [
      'SEARCHING',
      'DRIVER_OFFERED',
      'CANCELLING',
      'ACCEPTED',
      'IN_PROGRESS',
      'COMPLETED',
      'CANCELLED',
      'NO_DRIVER'
    ] as const
    for (const s of states) {
      expect(tripStateLabel(s).length).toBeGreaterThan(0)
      expect(typeof tripStateColor(s)).toBe('string')
    }
  })

  it('payment labels tiếng Việt', () => {
    expect(paymentStatusLabel('NOT_REQUESTED')).toBe('Chưa thanh toán')
    expect(paymentStatusLabel('PENDING')).toBe('Đang xử lý thanh toán')
    expect(paymentStatusLabel('SUCCEEDED')).toBe('Thanh toán thành công')
    expect(paymentStatusLabel('FAILED')).toBe('Thanh toán thất bại')
  })
})

describe('formatVnd', () => {
  it('định dạng VND vi-VN không số thập phân', () => {
    const s = formatVnd(22000)
    expect(s).toContain('22.000')
    expect(s).toContain('₫')
  })
})

describe('formatDateTime', () => {
  it('giữ nguyên chuỗi khi parse lỗi', () => {
    expect(formatDateTime('not-a-date')).toBe('not-a-date')
  })

  it('parse ISO hợp lệ → chuỗi không rỗng', () => {
    expect(formatDateTime('2026-10-27T09:30:00Z').length).toBeGreaterThan(0)
  })
})
