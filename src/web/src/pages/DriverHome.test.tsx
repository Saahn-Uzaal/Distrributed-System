// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import DriverHome from './DriverHome'
import { saveSession } from '../auth/session'

const USER = { id: 'u1', email: 'd@x.com', fullName: 'Driver One', role: 'DRIVER' as const }

interface Call {
  method: string
  url: string
  body: unknown
}

/** Server giả: test đổi state/trips/offer giữa chừng, fetch trả theo state hiện tại. */
const server = {
  me: null as Record<string, unknown> | null,
  meStatus: 200,
  offer: null as Record<string, unknown> | null,
  trips: {} as Record<string, Record<string, unknown> | null>,
  acceptResponse: { status: 202, body: { tripId: 'trip-a', state: 'ACCEPTED' } } as { status: number; body: unknown },
  onAccept: null as null | (() => void),
  calls: [] as Call[]
}

function driverState(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    driverId: 'drv-1',
    displayName: 'Driver One',
    vehicle: { plateNumber: '51A-123.45', vehicleType: 'CAR' },
    status: 'OFFLINE',
    latitude: 10.5,
    longitude: 106.5,
    lastSeenAt: '2026-10-30T00:00:00Z',
    tripId: null,
    ...overrides
  }
}

function trip(tripId: string, state: string): Record<string, unknown> {
  return {
    tripId,
    state,
    pickup: { latitude: 21, longitude: 105 },
    destination: { latitude: 21.1, longitude: 105.1 },
    distanceM: 1000,
    fareVnd: 17000,
    paymentStatus: 'NOT_REQUESTED',
    driverId: 'drv-1',
    createdAt: '2026-10-30T00:00:00Z'
  }
}

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as unknown as Response
}

function fakeFetch(url: unknown, init?: RequestInit): Promise<Response> {
  const path = String(url)
  const method = init?.method ?? 'GET'
  server.calls.push({ method, url: path, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
  if (path === '/api/auth/me') return Promise.resolve(json(200, USER))
  if (path === '/api/drivers/me') {
    if (server.meStatus !== 200) return Promise.resolve(json(server.meStatus, { code: server.meStatus === 401 ? 'UNAUTHORIZED' : 'UPSTREAM_UNAVAILABLE', message: 'x' }))
    if (!server.me) return Promise.resolve(json(404, { code: 'NOT_FOUND', message: 'Driver profile not found.' }))
    return Promise.resolve(json(200, server.me))
  }
  if (path === '/api/drivers/me/offer') return Promise.resolve(json(200, server.offer))
  if (path === '/api/drivers/me/location') return Promise.resolve(json(200, { driverId: 'drv-1', lastSeenAt: '2026-10-30T00:00:10Z' }))
  if (path === '/api/drivers/me/availability') return Promise.resolve(json(200, { driverId: 'drv-1', status: 'AVAILABLE', lastSeenAt: '2026-10-30T00:00:10Z' }))
  if (path.endsWith('/accept')) {
    server.onAccept?.()
    return Promise.resolve(json(server.acceptResponse.status, server.acceptResponse.body))
  }
  const m = /^\/api\/trips\/([^/]+)$/.exec(path)
  if (m) {
    const t = server.trips[m[1]]
    return Promise.resolve(t ? json(200, t) : json(404, { code: 'NOT_FOUND', message: 'Trip not found.' }))
  }
  return Promise.resolve(json(500, { code: 'INTERNAL_ERROR', message: `unexpected ${method} ${path}` }))
}

async function tick(ms = 0): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

function renderPage(): void {
  render(
    <MemoryRouter initialEntries={['/driver']}>
      <Routes>
        <Route path="/driver" element={<DriverHome />} />
        <Route path="/login" element={<div>LOGIN PAGE</div>} />
      </Routes>
    </MemoryRouter>
  )
}

const puts = (path: string): Call[] => server.calls.filter((c) => c.method === 'PUT' && c.url === path)

beforeEach(() => {
  vi.useFakeTimers()
  sessionStorage.clear()
  saveSession('tok', USER, 3600)
  server.me = driverState()
  server.meStatus = 200
  server.offer = null
  server.trips = {}
  server.acceptResponse = { status: 202, body: { tripId: 'trip-a', state: 'ACCEPTED' } }
  server.onAccept = null
  server.calls = []
  vi.stubGlobal('fetch', fakeFetch)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('DriverHome — server là nguồn sự thật', () => {
  it('mở trang không đổi trạng thái: OFFLINE giữ OFFLINE, không PUT availability/location', async () => {
    renderPage()
    await tick()
    await tick(30_000)
    expect(screen.getByText('Nghỉ (offline)')).toBeTruthy()
    expect(puts('/api/drivers/me/availability')).toHaveLength(0)
    expect(puts('/api/drivers/me/location')).toHaveLength(0)
  })

  it('không dùng cache của phiên trước: sessionStorage cũ không ảnh hưởng', async () => {
    sessionStorage.setItem('ridehailing.driverStatus', JSON.stringify('AVAILABLE'))
    renderPage()
    await tick()
    expect(screen.getByText('Nghỉ (offline)')).toBeTruthy()
    expect(puts('/api/drivers/me/availability')).toHaveLength(0)
  })

  it('chưa có profile → form tạo hồ sơ', async () => {
    server.me = null
    renderPage()
    await tick()
    expect(screen.getByText('Tạo hồ sơ tài xế')).toBeTruthy()
  })

  it('401 khi đang polling → về trang đăng nhập', async () => {
    renderPage()
    await tick()
    server.meStatus = 401
    await tick(3000)
    expect(screen.getByText('LOGIN PAGE')).toBeTruthy()
  })

  it('polling lỗi khi đang có dữ liệu → báo dữ liệu có thể đã cũ', async () => {
    renderPage()
    await tick()
    server.meStatus = 502
    await tick(3000)
    expect(screen.getByText(/Không cập nhật được \(UPSTREAM_UNAVAILABLE\)/)).toBeTruthy()
    server.meStatus = 200
    await tick(3000)
    expect(screen.queryByText(/Không cập nhật được/)).toBeNull()
  })
})

describe('DriverHome — heartbeat', () => {
  it('AVAILABLE: gửi tọa độ server ngay rồi mỗi 10 s; bản nháp đang gõ không được gửi', async () => {
    server.me = driverState({ status: 'AVAILABLE' })
    renderPage()
    await tick()
    expect(puts('/api/drivers/me/location')).toHaveLength(1)
    expect(puts('/api/drivers/me/location')[0].body).toEqual({ latitude: 10.5, longitude: 106.5 })

    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '99' } })
    await tick(10_000)
    const sent = puts('/api/drivers/me/location')
    expect(sent).toHaveLength(2)
    expect(sent[1].body).toEqual({ latitude: 10.5, longitude: 106.5 })
  })

  it('chưa có vị trí trên server: không gửi tọa độ mặc định, nhắc cập nhật', async () => {
    server.me = driverState({ status: 'AVAILABLE', latitude: null, longitude: null })
    renderPage()
    await tick()
    await tick(10_000)
    expect(puts('/api/drivers/me/location')).toHaveLength(0)
    expect(screen.getByText(/Chưa có vị trí/)).toBeTruthy()
  })
})

describe('DriverHome — offer và chuyến', () => {
  const offer = {
    tripId: 'trip-a',
    attempt: 1,
    pickup: { latitude: 21, longitude: 105 },
    destination: { latitude: 21.1, longitude: 105.1 },
    distanceM: 1000,
    fareVnd: 17000,
    expiresAt: '2099-01-01T00:00:00Z'
  }

  it('offer hết hạn: không kẹt RESERVED, về AVAILABLE theo server và heartbeat chạy lại', async () => {
    server.me = driverState({ status: 'RESERVED', tripId: 'trip-a' })
    server.offer = offer
    renderPage()
    await tick()
    expect(screen.getByText('Nhận chuyến')).toBeTruthy()
    expect(puts('/api/drivers/me/location')).toHaveLength(0)

    // Offer hết hạn trước khi driver-service kịp giải phóng: vẫn RESERVED, offer đã null.
    server.offer = null
    await tick(3000)
    expect(screen.getByText('Đang chờ phản hồi offer')).toBeTruthy()

    server.me = driverState({ status: 'AVAILABLE' })
    await tick(3000)
    expect(screen.getByText('Đang hoạt động', { selector: '.ant-tag' })).toBeTruthy()
    expect(puts('/api/drivers/me/location')).toHaveLength(1)
    expect(puts('/api/drivers/me/availability')).toHaveLength(0)
  })

  it('chuyến cũ 404 không che chuyến mới: nút Bắt đầu hiện đúng cho chuyến sau', async () => {
    server.me = driverState({ status: 'BUSY', tripId: 'trip-old' })
    server.trips['trip-old'] = null
    renderPage()
    await tick()
    expect(screen.getByText(/Không tìm thấy chuyến này/)).toBeTruthy()

    server.me = driverState({ status: 'BUSY', tripId: 'trip-new' })
    server.trips['trip-new'] = trip('trip-new', 'ACCEPTED')
    await tick(3000)
    expect(screen.queryByText(/Không tìm thấy chuyến này/)).toBeNull()
    expect(screen.getByText('Bắt đầu chuyến')).toBeTruthy()
  })

  it('accept gặp 504 nhưng server đã confirm: không mất chuyến, hiện panel theo state server', async () => {
    server.me = driverState({ status: 'RESERVED', tripId: 'trip-a' })
    server.offer = offer
    server.trips['trip-a'] = trip('trip-a', 'ACCEPTED')
    server.acceptResponse = { status: 504, body: { code: 'UPSTREAM_UNAVAILABLE', message: 'x' } }
    server.onAccept = () => {
      server.me = driverState({ status: 'BUSY', tripId: 'trip-a' })
      server.offer = null
    }
    renderPage()
    await tick()
    fireEvent.click(screen.getByText('Nhận chuyến'))
    await tick()
    await tick()
    expect(screen.queryByText(/UPSTREAM_UNAVAILABLE/)).toBeNull()
    expect(screen.getByText('Bắt đầu chuyến')).toBeTruthy()
  })

  it('accept bị từ chối thật (409) → hiện lỗi', async () => {
    server.me = driverState({ status: 'RESERVED', tripId: 'trip-a' })
    server.offer = offer
    server.acceptResponse = { status: 409, body: { code: 'STATE_CONFLICT', message: 'Offer is no longer valid.' } }
    renderPage()
    await tick()
    fireEvent.click(screen.getByText('Nhận chuyến'))
    await tick()
    expect(screen.getByText(/STATE_CONFLICT: Offer is no longer valid/)).toBeTruthy()
  })
})
