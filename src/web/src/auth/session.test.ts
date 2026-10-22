// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, loadSession, routeForRole, saveSession } from './session'

const USER = { id: 'u1', email: 'a@x.com', fullName: 'A B', role: 'PASSENGER' as const }

describe('routeForRole — route theo role (T08 acceptance)', () => {
  it('PASSENGER → /passenger', () => {
    expect(routeForRole('PASSENGER')).toBe('/passenger')
  })
  it('DRIVER → /driver', () => {
    expect(routeForRole('DRIVER')).toBe('/driver')
  })
})

describe('N34 — session có expiresAt, hết hạn coi như không có phiên', () => {
  afterEach(() => {
    sessionStorage.clear()
    vi.useRealTimers()
  })

  it('saveSession lưu expiresAt = now + expiresIn*1000; loadSession trả phiên khi chưa hết hạn', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    saveSession('tok-a', USER, 60)
    const s = loadSession()
    expect(s).not.toBeNull()
    expect(s!.token).toBe('tok-a')
    expect(s!.expiresAt).toBe(Date.now() + 60_000)
  })

  it('loadSession trả null VÀ xóa item khi session đã hết hạn', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    saveSession('tok-b', USER, 60)
    vi.advanceTimersByTime(61_000)
    expect(loadSession()).toBeNull()
    // Không để lại item hết hạn trong sessionStorage.
    expect(sessionStorage.getItem('ridehailing.session')).toBeNull()
  })

  it('dữ liệu cũ không có expiresAt được coi là chưa hết hạn (backward compat)', () => {
    sessionStorage.setItem(
      'ridehailing.session',
      JSON.stringify({ token: 'tok-c', user: USER })
    )
    const s = loadSession()
    expect(s).not.toBeNull()
    expect(s!.token).toBe('tok-c')
  })

  it('clearSession xóa item', () => {
    saveSession('tok-d', USER, 60)
    clearSession()
    expect(loadSession()).toBeNull()
  })
})
