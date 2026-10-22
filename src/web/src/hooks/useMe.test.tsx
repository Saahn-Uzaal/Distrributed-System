// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useMe } from './useMe'
import { clearSession, saveSession } from '../auth/session'

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

describe('useMe — F19 phân biệt lỗi AUTH và NETWORK', () => {
  it('200 -> user được set, không error', async () => {
    saveSession('tok', USER, 3600)
    vi.stubGlobal('fetch', async () => fakeResponse(200, USER))
    const { result } = renderHook(() => useMe())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBeNull()
    expect(result.current.user?.id).toBe('u1')
  })

  it('401 UNAUTHORIZED -> error AUTH (client đã xóa session khớp token)', async () => {
    saveSession('tok', USER, 3600)
    vi.stubGlobal(
      'fetch',
      async () => fakeResponse(401, { code: 'UNAUTHORIZED', message: 'Phiên hết hạn.' })
    )
    const { result } = renderHook(() => useMe())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('AUTH')
    expect(result.current.user).toBeNull()
  })

  it('500 -> error NETWORK; retry() gọi lại và thành công', async () => {
    saveSession('tok', USER, 3600)
    let calls = 0
    vi.stubGlobal(
      'fetch',
      async () => {
        calls++
        return calls === 1
          ? fakeResponse(500, { code: 'INTERNAL_ERROR', message: 'Lỗi hệ thống.' })
          : fakeResponse(200, USER)
      }
    )
    const { result } = renderHook(() => useMe())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('NETWORK')

    result.current.retry()
    // Poll tới khi user được set (chống flake do scheduling khi chạy song song).
    await waitFor(() => expect(result.current.user?.id).toBe('u1'))
    expect(result.current.error).toBeNull()
    expect(calls).toBe(2)
  })
})
