import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { SessionUser } from '../auth/session'

/** F19 — phân biệt lỗi /me: AUTH (401 -> về login) và NETWORK (mạng/5xx -> nút "Thử lại"). */
export type MeError = 'AUTH' | 'NETWORK'

/** GET /api/auth/me — xác thực lại session với server (không tin chỉ client). */
export function useMe(): {
  user: SessionUser | null
  loading: boolean
  error: MeError | null
  retry: () => void
} {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<MeError | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    api.me()
      .then((u) => {
        if (!cancelled) setUser(u)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        // 401 UNAUTHORIZED -> phiên bị server từ chối (client đã xóa session);
        // mọi lỗi khác (mạng, timeout, 5xx) -> NETWORK để UI cho "Thử lại".
        setError(e instanceof ApiError && e.code === 'UNAUTHORIZED' ? 'AUTH' : 'NETWORK')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [attempt])

  const retry = useCallback(() => setAttempt((a) => a + 1), [])

  return { user, loading, error, retry }
}
