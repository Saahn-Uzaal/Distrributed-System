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
  /** N34 — thời điểm (epoch ms) token hết hạn; loadSession() trả null khi đã quá. */
  expiresAt: number
}

const STORAGE_KEY = 'ridehailing.session'

/**
 * Lưu session kèm thời điểm hết hạn tính từ `expiresInSeconds` của response login
 * (contract §3.1: expiresIn = giây tới exp của JWT).
 */
export function saveSession(token: string, user: SessionUser, expiresInSeconds: number): void {
  const value: StoredSession = {
    token,
    user,
    expiresAt: Date.now() + Math.max(0, expiresInSeconds) * 1000
  }
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value))
}

export function loadSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredSession>
    if (typeof parsed.token !== 'string' || !parsed.user) return null
    if (parsed.user.role !== 'PASSENGER' && parsed.user.role !== 'DRIVER') return null
    // N34: session đã hết hạn -> coi như không có phiên (server sẽ trả 401 cho mọi call).
    const expiresAt = typeof parsed.expiresAt === 'number' ? parsed.expiresAt : Number.MAX_SAFE_INTEGER
    if (expiresAt <= Date.now()) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return { token: parsed.token, user: parsed.user, expiresAt }
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
