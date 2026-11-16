// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import DemoOverview from './DemoOverview'
import { saveSession } from '../auth/session'

const TOKEN = 'secret-jwt-value'
const USER = { id: 'p1', email: 'p@x.com', fullName: 'P One', role: 'PASSENGER' as const }

const TRIP = {
  tripId: 'trip-1-abcdef',
  state: 'COMPLETED',
  pickup: { latitude: 21, longitude: 105 },
  destination: { latitude: 21.1, longitude: 105.1 },
  distanceM: 1000,
  fareVnd: 17000,
  paymentStatus: 'SUCCEEDED',
  driverId: null,
  createdAt: '2026-10-30T00:00:00Z'
}

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as unknown as Response
}

function page<T>(content: T[]): unknown {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }
}

beforeEach(() => {
  sessionStorage.clear()
  saveSession(TOKEN, USER, 3600)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('DemoOverview', () => {
  it('xem lịch sử sự kiện của chuyến đã chọn, không lộ token', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
      if (String(url).startsWith('/api/trips?')) return json(200, page([TRIP]))
      if (String(url).startsWith('/api/trips/trip-1-abcdef/events')) {
        return json(200, page([{ type: 'TRIP_COMPLETED', version: 5, actorId: 'd1', summary: 'Driver completed the trip', createdAt: '2026-10-30T00:00:09Z' }]))
      }
      return json(404, { code: 'NOT_FOUND', message: 'x' })
    }))
    render(
      <MemoryRouter>
        <DemoOverview />
      </MemoryRouter>
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Xem lịch sử' }))

    expect(await screen.findByText('TRIP_COMPLETED')).toBeTruthy()
    expect(screen.getByText('Driver completed the trip')).toBeTruthy()
    expect(document.body.textContent).not.toContain(TOKEN)
  })

  it('không có quyền xem lịch sử: báo lỗi thay vì bảng rỗng', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: unknown) =>
      String(url).startsWith('/api/trips?') ? json(200, page([TRIP])) : json(404, { code: 'NOT_FOUND', message: 'x' })
    ))
    render(
      <MemoryRouter>
        <DemoOverview />
      </MemoryRouter>
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Xem lịch sử' }))
    expect(await screen.findByText('Không tải được lịch sử: NOT_FOUND')).toBeTruthy()
  })
})
