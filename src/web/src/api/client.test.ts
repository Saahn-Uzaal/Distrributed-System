// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, REQUEST_TIMEOUT_MS } from './client'
import { clearSession, loadSession, saveSession } from '../auth/session'

const USER = { id: 'u1', email: 'a@x.com', fullName: 'A B', role: 'PASSENGER' as const }

/** Response giả đủ các trường client.ts dùng (ok/status/text) — không phụ thuộc jsdom/Response. */
function fakeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  } as unknown as Response
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  clearSession()
})

describe('client — correlation ID (contract §5)', () => {
  it('mỗi request gửi header X-Correlation-ID là UUID v4', async () => {
    const seen: string[] = []
    vi.stubGlobal(
      'fetch',
      async (_url: unknown, init?: RequestInit) => {
        seen.push(String((init?.headers as Record<string, string>)['X-Correlation-ID']))
        return fakeResponse(200, { accessToken: 't', expiresIn: 60, user: USER })
      }
    )
    await api.login('a@x.com', 'secret123')
    expect(seen).toHaveLength(1)
    expect(seen[0]).toMatch(UUID_RE)
  })

  it('response lỗi mang correlationId từ body vào ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      async () => fakeResponse(401, { code: 'INVALID_CREDENTIALS', message: 'Sai thông tin đăng nhập.', correlationId: 'cid-123' })
    )
    const err = await api.login('a@x.com', 'wrong').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('INVALID_CREDENTIALS')
    expect((err as ApiError).correlationId).toBe('cid-123')
  })
})

describe('F15 — 401 chỉ xóa session nếu token đang lưu vẫn là token đã gửi', () => {
  it('request cũ hoàn tất muộn (token A) KHÔNG xóa phiên mới (token B)', async () => {
    saveSession('token-A', USER, 3600)
    let resolveFn: ((r: Response) => void) | null = null
    const pending = new Promise<Response>((res) => {
      resolveFn = res
    })
    vi.stubGlobal('fetch', async () => pending)

    const p = api.me() // gửi đi với token-A
    saveSession('token-B', USER, 3600) // người dùng đăng nhập lại trong lúc chờ
    resolveFn!(fakeResponse(401, { code: 'UNAUTHORIZED', message: 'Phiên hết hạn.' }))

    await expect(p).rejects.toBeInstanceOf(ApiError)
    const current = loadSession()
    expect(current?.token).toBe('token-B') // phiên mới được giữ
  })

  it('401 với token khớp -> session bị xóa (hành vi cũ vẫn đúng)', async () => {
    saveSession('token-A', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async () => fakeResponse(401, { code: 'UNAUTHORIZED', message: 'Phiên hết hạn.' })
    )
    await expect(api.me()).rejects.toBeInstanceOf(ApiError)
    expect(loadSession()).toBeNull()
  })

  it('login (authenticated=false) không đụng session dù 401', async () => {
    saveSession('token-A', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async () => fakeResponse(401, { code: 'INVALID_CREDENTIALS', message: 'Sai thông tin đăng nhập.' })
    )
    await expect(api.login('a@x.com', 'wrong')).rejects.toBeInstanceOf(ApiError)
    expect(loadSession()?.token).toBe('token-A')
  })
})

describe('N34 — timeout / hủy request / lỗi mạng với message rõ ràng', () => {
  it('fetch treo quá REQUEST_TIMEOUT_MS -> ApiError REQUEST_TIMEOUT', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      (_url: unknown, init?: RequestInit) =>
        new Promise<Response>((_res, rej) => {
          const signal = init?.signal!
          if (signal.aborted) return void rej(new DOMException('Aborted', 'AbortError'))
          signal.addEventListener('abort', () => rej(new DOMException('Aborted', 'AbortError')))
        })
    )
    const p = api.login('a@x.com', 'secret123')
    vi.advanceTimersByTime(REQUEST_TIMEOUT_MS + 50)
    await expect(p).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' })
  })

  it('signal đã abort từ caller -> ApiError REQUEST_ABORTED (không gọi mạng)', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', (_url: unknown, init?: RequestInit) => {
      throw new DOMException(`Aborted (signal=${init?.signal?.aborted})`, 'AbortError')
    })
    await expect(api.login('a@x.com', 'secret123', controller.signal)).rejects.toMatchObject({
      code: 'REQUEST_ABORTED'
    })
  })

  it('fetch ném lỗi mạng -> ApiError NETWORK_ERROR (không TypeError trần)', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('fetch failed')))
    const err = await api.login('a@x.com', 'secret123').catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).code).toBe('NETWORK_ERROR')
  })
})
