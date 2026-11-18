// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import CreateTripForm from './CreateTripForm'
import { saveSession } from '../../auth/session'

vi.mock('../../maps/RouteMap', () => ({ default: () => <div aria-label="Bản đồ tuyến đường">route</div> }))

const USER = { id: 'p1', email: 'p@x.com', fullName: 'P One', role: 'PASSENGER' as const }
const pickup = { locationToken: 'p-loc', displayName: 'Hồ Gươm, Hà Nội', latitude: 21.0288, longitude: 105.8525 }
const destination = { locationToken: 'd-loc', displayName: 'Lăng Bác, Hà Nội', latitude: 21.0368, longitude: 105.8347 }

function response(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as Response
}

beforeEach(() => { sessionStorage.clear(); saveSession('token', USER, 3600) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function selectAddress(label: 'Điểm đón' | 'Điểm đến', query: string, result: typeof pickup): Promise<void> {
  fireEvent.change(screen.getByLabelText(label), { target: { value: query } })
  fireEvent.click(screen.getByRole('button', { name: `Tìm ${label.toLocaleLowerCase('vi-VN')}` }))
  await screen.findByText(result.displayName)
  fireEvent.click(screen.getByText(result.displayName))
}

it('searches addresses, reviews route and creates only from quote token', async () => {
  const requests: Array<{ url: string; body?: Record<string, unknown> }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    const item = { url: String(url), body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined }
    requests.push(item)
    if (item.url.includes('H%E1%BB%93')) return response(200, { results: [pickup] })
    if (item.url.includes('L%C4%83ng')) return response(200, { results: [destination] })
    if (item.url === '/api/trips/quote') return response(200, {
      quoteToken: 'quote-1', pickup, destination, distanceM: 2400, durationS: 600, fareVnd: 27000,
      routeGeometry: { type: 'LineString', coordinates: [[105.8525, 21.0288], [105.8347, 21.0368]] },
      expiresAt: new Date(Date.now() + 600_000).toISOString()
    })
    return response(202, { tripId: 'trip-1', state: 'SEARCHING', matchingAttempt: 1, createdAt: new Date().toISOString() })
  }))
  const created = vi.fn()
  render(<CreateTripForm onCreated={created} />)

  await selectAddress('Điểm đón', 'Hồ Gươm', pickup)
  await selectAddress('Điểm đến', 'Lăng Bác', destination)
  fireEvent.click(screen.getByRole('button', { name: 'Xem tuyến đường và giá' }))
  await screen.findByText(/2,4 km/)
  expect(screen.getByText(/10 phút/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Đặt chuyến' }))
  await waitFor(() => expect(created).toHaveBeenCalledOnce())

  expect(requests.find((r) => r.url === '/api/trips/quote')?.body)
    .toEqual({ pickupLocationToken: 'p-loc', destinationLocationToken: 'd-loc' })
  expect(requests.find((r) => r.url === '/api/trips')?.body).toEqual({ quoteToken: 'quote-1' })
})
