import { clearSession, loadSession } from '../auth/session'
import type { Role, SessionUser } from '../auth/session'

/**
 * N16 fix: `crypto.randomUUID()` chỉ có trong secure context (https/localhost).
 * Mở http://<IP>:8080 từ máy khác → mọi API call ném TypeError.
 * Fallback: dựng UUID v4 từ `crypto.getRandomValues` (có sẵn ở cả HTTP thường).
 */
export function uuidV4Fallback(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // variant 10
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function newUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return uuidV4Fallback()
}

/** Error shape theo contract §1.1: {code, message, correlationId, details}. */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly correlationId?: string
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export interface RegisterBody {
  email: string
  password: string
  fullName: string
  role: Role
}

async function request<T>(
  path: string,
  options: { method?: string; body?: unknown; authenticated?: boolean } = {}
): Promise<T> {
  const session = loadSession()
  const headers: Record<string, string> = {
    // contract §5: client sinh UUID correlation ID, server echo lại trong response/error
    'X-Correlation-ID': newUuid()
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.authenticated !== false && session) {
    // Token chỉ đi trong header Authorization — không log, không console.
    headers['Authorization'] = `Bearer ${session.token}`
  }

  const resp = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined
  })

  const text = await resp.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }

  if (!resp.ok) {
    // R03 fix: server từ chối phiên (401) -> xóa session local ngay để UI không tiếp tục
    // hiển thị "đăng nhập thành công" từ cache cũ; phân biệt với lỗi mạng/server khác.
    if (resp.status === 401 && options.authenticated !== false && session) {
      clearSession()
    }
    const err = (data ?? {}) as { code?: string; message?: string; correlationId?: string }
    throw new ApiError(err.code ?? 'UNKNOWN', err.message ?? `HTTP ${resp.status}`, err.correlationId)
  }

  return data as T
}

export const api = {
  register(body: RegisterBody): Promise<{ user: SessionUser }> {
    return request('/api/auth/register', { method: 'POST', body, authenticated: false })
  },
  login(email: string, password: string): Promise<{ accessToken: string; expiresIn: number; user: SessionUser }> {
    return request('/api/auth/login', { method: 'POST', body: { email, password }, authenticated: false })
  },
  me(): Promise<SessionUser> {
    return request('/api/auth/me')
  }
}
