// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import TripDetailPage from './TripDetailPage'
import { saveSession } from '../auth/session'

const USER = { id: 'p1', email: 'p@x.com', fullName: 'P One', role: 'PASSENGER' as const }

function trip(state: string, paymentStatus: string): Record<string, unknown> {
  return {
    tripId: 'trip-1-abcdef',
    state,
    pickup: { latitude: 21, longitude: 105 },
    destination: { latitude: 21.1, longitude: 105.1 },
    distanceM: 1000,
    fareVnd: 17000,
    paymentStatus,
    driverId: null,
    matchingAttempt: 1,
    createdAt: '2026-10-30T00:00:00Z'
  }
}

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as unknown as Response
}

let current: () => Promise<Response>
let calls = 0

async function tick(ms = 0): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/passenger/trips/trip-1-abcdef']}>
      <Routes>
        <Route path="/passenger/trips/:tripId" element={<TripDetailPage />} />
        <Route path="/login" element={<div>LOGIN PAGE</div>} />
      </Routes>
    </MemoryRouter>
  )
}

beforeEach(() => {
  vi.useFakeTimers()
  sessionStorage.clear()
  saveSession('tok', USER, 3600)
  calls = 0
  vi.stubGlobal('fetch', () => {
    calls += 1
    return current()
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('TripDetailPage', () => {
  it('COMPLETED còn PENDING vẫn poll tới khi có kết quả thanh toán, rồi dừng', async () => {
    current = async () => json(200, trip('COMPLETED', 'PENDING'))
    renderPage()
    await tick()
    expect(screen.getByText(/đang xử lý, kết quả sẽ cập nhật tự động/)).toBeTruthy()

    await tick(3000)
    expect(calls).toBe(2) // chưa dừng dù trip đã terminal

    current = async () => json(200, trip('COMPLETED', 'SUCCEEDED'))
    await tick(3000)
    expect(screen.getByText('Thanh toán thành công')).toBeTruthy()

    const settledCalls = calls
    await tick(30_000)
    expect(calls).toBe(settledCalls)
  })

  it('lỗi ngay lần đầu: hiện lỗi + Thử lại, không quay spinner mãi', async () => {
    current = async () => json(502, { code: 'UPSTREAM_UNAVAILABLE', message: 'x' })
    renderPage()
    await tick()
    expect(screen.getByText('Không tải được chuyến: UPSTREAM_UNAVAILABLE')).toBeTruthy()

    current = async () => json(200, trip('SEARCHING', 'NOT_REQUESTED'))
    fireEvent.click(screen.getByText('Thử lại'))
    await tick()
    expect(screen.getByText('Đang tìm tài xế')).toBeTruthy()
  })

  it('lỗi khi đang có dữ liệu: báo dữ liệu có thể đã cũ', async () => {
    current = async () => json(200, trip('SEARCHING', 'NOT_REQUESTED'))
    renderPage()
    await tick()
    current = async () => json(502, { code: 'UPSTREAM_UNAVAILABLE', message: 'x' })
    await tick(3000)
    expect(screen.getByText(/Không cập nhật được \(UPSTREAM_UNAVAILABLE\)/)).toBeTruthy()
    expect(screen.getByText('Đang tìm tài xế')).toBeTruthy()
  })

  it('401 → về trang đăng nhập', async () => {
    current = async () => json(401, { code: 'UNAUTHORIZED', message: 'x' })
    renderPage()
    await tick()
    expect(screen.getByText('LOGIN PAGE')).toBeTruthy()
  })
})
