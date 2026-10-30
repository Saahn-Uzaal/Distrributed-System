/** Token hết hạn/bị từ chối giữa lúc polling: client đã xóa session — đưa người dùng về trang đăng nhập. */
import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ApiError } from '../api/client'

export function useUnauthorizedRedirect(error: ApiError | null): void {
  const navigate = useNavigate()
  const unauthorized = error?.code === 'UNAUTHORIZED'
  useEffect(() => {
    if (unauthorized) navigate('/login', { replace: true })
  }, [unauthorized, navigate])
}
