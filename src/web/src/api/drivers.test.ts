// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../auth/session'
import { ApiError } from './client'
import { driversApi } from './drivers'

const USER = { id: 'd1', email: 'driver@x.com', fullName: 'D A', role: 'DRIVER' as const }

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

describe('driversApi (contract §3.2)', () => {
  it('createProfile POST body displayName + vehicle', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; body?: unknown } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = { url: String(url), body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined }
        return fakeResponse(201, {
          driverId: 'drv-1',
          displayName: 'Driver A',
          vehicle: { plateNumber: '51A-123.45', vehicleType: 'MOTORBIKE' },
          status: 'OFFLINE'
        })
      }
    )
    const res = await driversApi.createProfile('Driver A', '51A-123.45', 'MOTORBIKE')
    expect(res.driverId).toBe('drv-1')
    expect(seen!.url).toBe('/api/drivers/me/profile')
    expect(seen!.body).toEqual({ displayName: 'Driver A', vehicle: { plateNumber: '51A-123.45', vehicleType: 'MOTORBIKE' } })
  })

  it('setAvailability PUT body {status}', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; method?: string; body?: unknown } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = { url: String(url), method: init?.method, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined }
        return fakeResponse(200, { driverId: 'drv-1', status: 'AVAILABLE', lastSeenAt: '2026-10-29T10:00:00Z' })
      }
    )
    const res = await driversApi.setAvailability('AVAILABLE')
    expect(res.status).toBe('AVAILABLE')
    expect(seen!.url).toBe('/api/drivers/me/availability')
    expect(seen!.method).toBe('PUT')
    expect(seen!.body).toEqual({ status: 'AVAILABLE' })
  })

  it('setLocation PUT body {latitude, longitude}', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; body?: unknown } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = { url: String(url), body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined }
        return fakeResponse(200, { driverId: 'drv-1', lastSeenAt: '2026-10-29T10:00:05Z' })
      }
    )
    await driversApi.setLocation(21.0345, 105.8409)
    expect(seen!.url).toBe('/api/drivers/me/location')
    expect(seen!.body).toEqual({ latitude: 21.0345, longitude: 105.8409 })
  })

  it('getMe GET /api/drivers/me — chỉ đọc, không gửi body', async () => {
    saveSession('tok', USER, 3600)
    let seen: { url: string; method?: string; body?: unknown } | null = null
    vi.stubGlobal(
      'fetch',
      async (url: unknown, init?: RequestInit) => {
        seen = { url: String(url), method: init?.method, body: init?.body }
        return fakeResponse(200, { driverId: 'drv-1', status: 'OFFLINE', tripId: null })
      }
    )
    const res = await driversApi.getMe()
    expect(res.status).toBe('OFFLINE')
    expect(seen).toEqual({ url: '/api/drivers/me', method: 'GET', body: undefined })
  })

  it('getOffer trả null literal khi không có offer (chốt T17)', async () => {
    saveSession('tok', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async (url: unknown) => {
        expect(String(url)).toBe('/api/drivers/me/offer')
        return fakeResponse(200, null)
      }
    )
    const res = await driversApi.getOffer()
    expect(res).toBeNull()
  })

  it('409 STATE_CONFLICT giữ details {state, tripId} trong ApiError', async () => {
    saveSession('tok', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async () =>
        fakeResponse(409, {
          code: 'STATE_CONFLICT',
          message: 'Driver is not free to change availability while BUSY.',
          correlationId: 'cid-1',
          details: { state: 'BUSY', tripId: 'trip-1' }
        })
    )
    const err = await driversApi.setAvailability('OFFLINE').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('STATE_CONFLICT')
    expect((err as ApiError).details).toEqual({ state: 'BUSY', tripId: 'trip-1' })
  })
})
