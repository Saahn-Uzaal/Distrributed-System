import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SessionUser } from '../auth/session'

/** GET /api/auth/me — xác thực lại session với server (không tin chỉ client). */
export function useMe(): { user: SessionUser | null; loading: boolean } {
  const [user, setUser] = useState<SessionUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    api.me()
      .then((u) => {
        if (!cancelled) setUser(u)
      })
      .catch(() => {
        // token hỏng/hết hạn: giữ user=null, UI hiển thị trạng thái chưa xác thực
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { user, loading }
}
