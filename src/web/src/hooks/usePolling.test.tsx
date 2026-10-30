// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { ApiError } from '../api/client'
import { usePolling } from './usePolling'

afterEach(() => {
  vi.useRealTimers()
})

describe('usePolling (acceptance T27 "polling cleanup")', () => {
  it('fetch ngay khi mount, poll theo interval, DỪNG khi stopWhen = true', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetcher = async (_signal?: AbortSignal) => {
      calls += 1
      return { state: (calls >= 2 ? 'COMPLETED' : 'SEARCHING') as 'SEARCHING' | 'COMPLETED' }
    }
    const { result } = renderHook(() =>
      usePolling(fetcher, { intervalMs: 1000, stopWhen: (d) => d.state === 'COMPLETED' })
    )

    await act(async () => {})
    expect(calls).toBe(1)
    expect(result.current.data?.state).toBe('SEARCHING')

    await act(async () => {
      vi.advanceTimersByTime(1000)
    })
    expect(calls).toBe(2)
    expect(result.current.data?.state).toBe('COMPLETED')

    // Đã terminal → không poll nữa dù timer vẫn chạy.
    await act(async () => {
      vi.advanceTimersByTime(10_000)
    })
    expect(calls).toBe(2)
  })

  it('UNAUTHORIZED luôn dừng polling kể cả khi không khai báo', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetcher = async (_signal?: AbortSignal): Promise<number> => {
      calls += 1
      throw new ApiError('UNAUTHORIZED', 'expired')
    }
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 1000 }))
    await act(async () => {})
    await act(async () => {
      vi.advanceTimersByTime(5000)
    })
    expect(calls).toBe(1)
    expect(result.current.error?.code).toBe('UNAUTHORIZED')
  })

  it('dừng hẳn khi lỗi thuộc stopOnErrorCodes (vd NOT_FOUND)', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetcher = async (_signal?: AbortSignal) => {
      calls += 1
      if (calls === 2) throw new ApiError('NOT_FOUND', 'Trip not found.')
      return { ok: true }
    }
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 1000, stopOnErrorCodes: ['NOT_FOUND'] }))

    await act(async () => {})
    expect(calls).toBe(1)

    await act(async () => {
      vi.advanceTimersByTime(1000)
    })
    expect(calls).toBe(2)
    expect(result.current.error?.code).toBe('NOT_FOUND')

    await act(async () => {
      vi.advanceTimersByTime(10_000)
    })
    expect(calls).toBe(2) // không poll tiếp
  })

  it('unmount → hủy request đang bay và không poll nữa (cleanup)', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetcher = (signal?: AbortSignal) =>
      new Promise((_resolve: unknown, reject: (e: Error) => void) => {
        calls += 1
        signal?.addEventListener('abort', () => reject(new ApiError('REQUEST_ABORTED', 'hủy')))
      })

    const { unmount } = renderHook(() => usePolling(fetcher, { intervalMs: 1000 }))
    await act(async () => {}) // request đầu tiên đang bay (chưa resolve)
    expect(calls).toBe(1)

    unmount()
    await act(async () => {
      vi.advanceTimersByTime(30_000)
    })
    expect(calls).toBe(1) // không có poll mới sau unmount
  })

  it('refresh() fetch lại ngay (dùng sau thao tác cancel/create)', async () => {
    vi.useFakeTimers()
    let calls = 0
    const fetcher = async (_signal?: AbortSignal) => {
      calls += 1
      return { n: calls }
    }
    const { result } = renderHook(() => usePolling(fetcher, { intervalMs: 60_000 }))

    await act(async () => {})
    expect(calls).toBe(1)

    await act(async () => {
      result.current.refresh()
    })
    expect(calls).toBe(2)
    expect(result.current.data?.n).toBe(2)
  })
})
