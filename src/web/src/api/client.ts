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
    public readonly correlationId?: string,
    /** contract §1.1 — details (vd STATE_CONFLICT driver: {state, tripId}). */
    public readonly details?: unknown
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** N34 — timeout mặc định cho mọi API call (AbortController). */
export const REQUEST_TIMEOUT_MS = 5000

export interface RegisterBody {
  email: string
  password: string
  fullName: string
  role: Role
}

export interface RequestOptions {
  method?: string
  body?: unknown
  /** T27 — header bổ sung (vd Idempotency-Key cho POST /api/trips). */
  extraHeaders?: Record<string, string>
  authenticated?: boolean
  /** N34 — timeout riêng cho call này (test); mặc định REQUEST_TIMEOUT_MS. */
  timeoutMs?: number
  /** F16 — signal để hủy request khi component unmount. */
  signal?: AbortSignal
}

/** T27 — HTTP client dùng chung cho mọi module API (trips/drivers). */
export async function http<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const session = loadSession()
  // F15: ghi lại token ĐÃ GỬI đi; chỉ xóa session nếu token đang lưu vẫn là token đó.
  const sentToken = session?.token ?? null
  const headers: Record<string, string> = {
    // contract §5: client sinh UUID correlation ID; service canonicalize và echo lại
    // trong response header X-Correlation-ID + body lỗi (T07-10/N13).
    'X-Correlation-ID': newUuid()
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.extraHeaders) {
    for (const [name, value] of Object.entries(options.extraHeaders)) headers[name] = value
  }
  if (options.authenticated !== false && session) {
    // Token chỉ đi trong header Authorization — không log, không console.
    headers['Authorization'] = `Bearer ${session.token}`
  }

  const controller = new AbortController()
  const timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  // N34: timeout luôn áp dụng; nếu caller truyền signal (unmount) thì kết hợp cả hai.
  let signal: AbortSignal | undefined = options.signal ?? controller.signal
  if (options.signal && typeof AbortSignal !== 'undefined') {
    const anyFn = (AbortSignal as unknown as { any?: (signals: AbortSignal[]) => AbortSignal }).any
    if (typeof anyFn === 'function') signal = anyFn.call(AbortSignal, [controller.signal, options.signal])
  }
  let resp: Response
  try {
    resp = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal
    })
  } catch (e) {
    // N34: báo lỗi rõ ràng cho hủy/timeout/lỗi mạng — không ném TypeError trần.
    if (options.signal?.aborted) {
      throw new ApiError('REQUEST_ABORTED', 'Yêu cầu đã bị hủy.')
    }
    if (controller.signal.aborted) {
      throw new ApiError('REQUEST_TIMEOUT', `Yêu cầu quá thời hạn sau ${Math.round(timeoutMs / 1000)} giây.`)
    }
    throw new ApiError('NETWORK_ERROR', 'Không thể kết nối máy chủ. Kiểm tra mạng và thử lại.')
  } finally {
    clearTimeout(timer)
  }

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
    // R03 + F15 fix: server từ chối phiên (401) -> xóa session local CHỈ nếu token đang lưu
    // vẫn là token đã gửi đi — request cũ hoàn tất muộn không được xóa phiên mới.
    if (resp.status === 401 && options.authenticated !== false && sentToken) {
      const current = loadSession()
      if (current?.token === sentToken) clearSession()
    }
    const err = (data ?? {}) as { code?: string; message?: string; correlationId?: string; details?: unknown }
    throw new ApiError(err.code ?? 'UNKNOWN', err.message ?? `HTTP ${resp.status}`, err.correlationId, err.details)
  }

  return data as T
}

export const api = {
  register(body: RegisterBody, signal?: AbortSignal): Promise<{ user: SessionUser }> {
    return http('/api/auth/register', { method: 'POST', body, authenticated: false, signal })
  },
  login(email: string, password: string, signal?: AbortSignal): Promise<{ accessToken: string; expiresIn: number; user: SessionUser }> {
    return http('/api/auth/login', { method: 'POST', body: { email, password }, authenticated: false, signal })
  },
  me(): Promise<SessionUser> {
    return http('/api/auth/me')
  }
}
