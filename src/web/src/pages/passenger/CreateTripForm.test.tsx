// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import CreateTripForm from './CreateTripForm'
import { saveSession } from '../../auth/session'

const USER = { id: 'p1', email: 'p@x.com', fullName: 'P One', role: 'PASSENGER' as const }

function json(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, text: async () => JSON.stringify(body) } as unknown as Response
}

const CREATED = { tripId: 'trip-1-abcdef', state: 'SEARCHING', matchingAttempt: 1, createdAt: '2026-10-30T00:00:00Z' }

/** Điền tọa độ qua 4 ô InputNumber: pickup lat/lng, destination lat/lng. */
function fillCoords(values = ['21.03', '105.84', '21.02', '105.85']): void {
  const inputs = screen.getAllByRole('spinbutton')
  values.forEach((value, i) => fireEvent.change(inputs[i], { target: { value } }))
}

/** antd Button bỏ qua click khi còn trạng thái loading nội bộ (tắt trong effect sau render): chờ tắt rồi mới bấm. */
async function clickCreate(): Promise<void> {
  const button = screen.getByRole('button', { name: /Đặt chuyến/ })
  await waitFor(() => expect(button.className).not.toContain('ant-btn-loading'))
  fireEvent.click(button)
}

function keysSent(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls
    .filter(([url]) => url === '/api/trips')
    .map(([, init]) => (init as { headers: Record<string, string> }).headers['Idempotency-Key'])
}

beforeEach(() => {
  sessionStorage.clear()
  saveSession('tok', USER, 3600)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('CreateTripForm — Idempotency-Key', () => {
  it('504 (kết quả chưa rõ) giữ key: bấm lại replay cùng key', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(504, { code: 'UPSTREAM_UNAVAILABLE', message: 'x' }))
      .mockResolvedValueOnce(json(202, CREATED))
    vi.stubGlobal('fetch', fetchMock)
    const onCreated = vi.fn()
    render(<CreateTripForm onCreated={onCreated} />)
    fillCoords()

    await clickCreate()
    await screen.findByText(/kết quả chưa rõ/)
    await clickCreate()
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1))

    const keys = keysSent(fetchMock)
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('lỗi nghiệp vụ (409) bỏ key: lần sau dùng key mới', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(409, { code: 'ACTIVE_TRIP_EXISTS', message: 'x' }))
      .mockResolvedValueOnce(json(202, CREATED))
    vi.stubGlobal('fetch', fetchMock)
    const onCreated = vi.fn()
    render(<CreateTripForm onCreated={onCreated} />)
    fillCoords()

    await clickCreate()
    await screen.findByText(/ACTIVE_TRIP_EXISTS/)
    await clickCreate()
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1))

    const keys = keysSent(fetchMock)
    expect(keys).toHaveLength(2)
    expect(keys[0]).not.toBe(keys[1])
  })

  it('sửa tọa độ sau lỗi chưa rõ: bỏ key cũ (tránh IDEMPOTENCY_MISMATCH)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(504, { code: 'UPSTREAM_UNAVAILABLE', message: 'x' }))
      .mockResolvedValueOnce(json(202, CREATED))
    vi.stubGlobal('fetch', fetchMock)
    const onCreated = vi.fn()
    render(<CreateTripForm onCreated={onCreated} />)
    fillCoords()

    await clickCreate()
    await screen.findByText(/kết quả chưa rõ/)
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '21.04' } })
    await clickCreate()
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1))

    const keys = keysSent(fetchMock)
    expect(keys[0]).not.toBe(keys[1])
  })
})

describe('CreateTripForm — quote', () => {
  it('quote của tọa độ cũ về muộn sau khi đã sửa tọa độ thì bị bỏ', async () => {
    let resolveQuote: (r: Response) => void = () => undefined
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { resolveQuote = resolve })))
    render(<CreateTripForm onCreated={() => undefined} />)
    fillCoords()

    fireEvent.click(screen.getByRole('button', { name: /Tính giá/ }))
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '21.5' } })
    await act(async () => {
      resolveQuote(json(200, { distanceM: 1234, fareVnd: 22000 }))
    })
    expect(screen.queryByText(/1234 m/)).toBeNull()
  })

  it('quote còn đúng tọa độ thì hiển thị', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { distanceM: 1234, fareVnd: 22000 })))
    render(<CreateTripForm onCreated={() => undefined} />)
    fillCoords()
    fireEvent.click(screen.getByRole('button', { name: /Tính giá/ }))
    await screen.findByText(/1234 m/)
  })
})
