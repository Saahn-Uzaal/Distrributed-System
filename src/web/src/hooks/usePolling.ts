/**
 * T27 — hook polling chung cho UI (spec §20.6 "polling cleanup").
 *
 * - Fetch ngay khi mount, sau đó mỗi `intervalMs`.
 * - Cleanup khi unmount: hủy request đang bay + dừng interval.
 * - Dừng hẳn khi `stopWhen(data)` = true (vd trip đạt terminal) hoặc lỗi thuộc
 *   `stopOnErrorCodes` (vd NOT_FOUND — không poll vô ích). UNAUTHORIZED luôn dừng: phiên đã mất.
 * - `refresh()` fetch lại ngay và khởi động lại chu kỳ polling.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'

export interface PollingState<T> {
  data: T | null
  error: ApiError | null
  loading: boolean
}

interface UsePollingOptions<T> {
  intervalMs?: number
  /** Dừng polling sau khi nhận data thỏa điều kiện (vd state terminal). */
  stopWhen?: (data: T) => boolean
  /** Dừng polling khi gặp lỗi có code trong danh sách. */
  stopOnErrorCodes?: string[]
}

export function usePolling<T>(
  fetcher: (signal?: AbortSignal) => Promise<T>,
  options: UsePollingOptions<T> = {}
): PollingState<T> & { refresh: () => void } {
  const [state, setState] = useState<PollingState<T>>({ data: null, error: null, loading: true })

  // Giữ ref để effect không re-run khi fetcher/options đổi (chỉ re-run qua tick).
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher
  const optionsRef = useRef(options)
  optionsRef.current = options

  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    let stopped = false
    let inFlight = false
    let timer: ReturnType<typeof setInterval> | undefined
    const controller = new AbortController()

    function stop(): void {
      stopped = true
      if (timer !== undefined) {
        clearInterval(timer)
        timer = undefined
      }
    }

    async function run(): Promise<void> {
      if (cancelled || stopped || inFlight) return
      inFlight = true
      try {
        const data = await fetcherRef.current(controller.signal)
        if (cancelled) return
        setState({ data, error: null, loading: false })
        if (optionsRef.current.stopWhen?.(data)) stop()
      } catch (e) {
        if (cancelled) return
        const err = e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e))
        setState((s) => ({ data: s.data, error: err, loading: false }))
        const codes = optionsRef.current.stopOnErrorCodes ?? []
        if (err.code === 'UNAUTHORIZED' || codes.includes(err.code)) stop()
      } finally {
        inFlight = false
        // Đặt lịch poll kế tiếp chỉ nếu vẫn active và chưa có interval.
        if (!cancelled && !stopped && timer === undefined) {
          timer = setInterval(run, optionsRef.current.intervalMs ?? 5000)
        }
      }
    }

    run()
    return () => {
      cancelled = true
      controller.abort()
      if (timer !== undefined) clearInterval(timer)
    }
  }, [tick])

  const refresh = useCallback(() => setTick((t) => t + 1), [])

  return { ...state, refresh }
}
