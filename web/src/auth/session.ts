/**
 * Session storage cho prototype (spec §11): sessionStorage — giữ trong tab,
 * tự xóa khi đóng tab. Token KHÔNG bao giờ được ghi ra console/log.
 */
export type Role = 'PASSENGER' | 'DRIVER'

export interface SessionUser {
  id: string
  email: string
  fullName: string
  role: Role
}

interface StoredSession {
  token: string
  user: SessionUser
}

const STORAGE_KEY = 'ridehailing.session'

export function saveSession(token: string, user: SessionUser): void {
  const value: StoredSession = { token, user }
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value))
}

export function loadSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredSession>
    if (typeof parsed.token !== 'string' || !parsed.user) return null
    if (parsed.user.role !== 'PASSENGER' && parsed.user.role !== 'DRIVER') return null
    return { token: parsed.token, user: parsed.user }
  } catch {
    return null
  }
}

export function clearSession(): void {
  sessionStorage.removeItem(STORAGE_KEY)
}

/** Ánh xạ thuần role → route home (unit-testable). */
export function routeForRole(role: Role): string {
  return role === 'DRIVER' ? '/driver' : '/passenger'
}
