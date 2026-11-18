// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../auth/session'
import { tripsApi } from './trips'

const USER = { id: 'u1', email: 'a@x.com', fullName: 'A B', role: 'PASSENGER' as const }

function fakeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

describe('tripsApi (contract §3.3/§3.4)', () => {
  it('createTrip gửi header Idempotency-Key + quoteToken', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; headers: Record<string, string>; body?: unknown } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = {
          url: String(url),
          headers: (init?.headers ?? {}) as Record<string, string>,
          body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
        }
        return fakeResponse(202, { tripId: 't1', state: 'SEARCHING', matchingAttempt: 1, createdAt: '2026-10-27T09:30:00Z' })
      }
    )

    const res = await tripsApi.createTrip('key-uuid', 'quote-uuid')
    expect(res.tripId).toBe('t1')
    expect(seen!.url).toBe('/api/trips')
    expect(seen!.headers['Idempotency-Key']).toBe('key-uuid')
    expect(seen!.body).toEqual({ quoteToken: 'quote-uuid' })
  })

  it('listTrips phân trang qua query page/size (contract §1.3)', async () => {
    saveSession('tok', USER, 3600)
    let url = ''
    vi.stubGlobal(
      'fetch',
      async (u: unknown) => {
        url = String(u)
        return fakeResponse(200, { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 })
      }
    )
    await tripsApi.listTrips(1)
    expect(url).toBe('/api/trips?page=1&size=20')
  })

  it('cancelTrip POST /api/trips/{id}/cancel không body', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; method?: string; hasBody: boolean } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = { url: String(url), method: init?.method, hasBody: typeof init?.body === 'string' }
        return fakeResponse(202, { tripId: 't1', state: 'CANCELLING' })
      }
    )
    const res = await tripsApi.cancelTrip('t1')
    expect(res.state).toBe('CANCELLING')
    expect(seen!.url).toBe('/api/trips/t1/cancel')
    expect(seen!.method).toBe('POST')
    expect(seen!.hasBody).toBe(false)
  })

  it('searchLocations mã hóa query và quote gửi hai location token', async () => {
    saveSession('tok', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        if (String(url).startsWith('/api/locations/search')) {
          expect(String(url)).toBe('/api/locations/search?q=H%E1%BB%93%20G%C6%B0%C6%A1m')
          return fakeResponse(200, { results: [{ locationToken: 'p1', displayName: 'Hồ Gươm', latitude: 21, longitude: 105 }] })
        }
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>
        expect(body).toEqual({ pickupLocationToken: 'p1', destinationLocationToken: 'd1' })
        return fakeResponse(200, { quoteToken: 'q1', distanceM: 1920, durationS: 420, fareVnd: 22000,
          pickup: { locationToken: 'p1', displayName: 'Hồ Gươm', latitude: 21, longitude: 105 },
          destination: { locationToken: 'd1', displayName: 'Lăng Bác', latitude: 21.03, longitude: 105.83 },
          routeGeometry: { type: 'LineString', coordinates: [[105, 21], [105.83, 21.03]] }, expiresAt: '2026-11-17T10:00:00Z' })
      }
    )
    const locations = await tripsApi.searchLocations('Hồ Gươm')
    expect(locations[0].locationToken).toBe('p1')
    const res = await tripsApi.quote('p1', 'd1')
    expect(res.quoteToken).toBe('q1')
    expect(res.durationS).toBe(420)
  })

  it('listNotifications GET /api/notifications phân trang', async () => {
    saveSession('tok', USER, 3600)
    let url = ''
    vi.stubGlobal(
      'fetch',
      async (u: unknown) => {
        url = String(u)
        return fakeResponse(200, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      }
    )
    await tripsApi.listNotifications()
    expect(url).toBe('/api/notifications?page=0&size=20')
  })
})
