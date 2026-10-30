/** Polling lỗi trong khi đang hiện dữ liệu cũ: báo rõ thay vì để người dùng tin màn hình còn mới. */
import type { ReactElement } from 'react'
import { Alert } from 'antd'
import type { ApiError } from '../api/client'

export default function StaleDataAlert({ error }: { error: ApiError | null }): ReactElement | null {
  if (!error || error.code === 'UNAUTHORIZED') return null
  return (
    <Alert
      style={{ marginBottom: 12 }}
      type="warning"
      message={`Không cập nhật được (${error.code}) — dữ liệu đang hiện có thể đã cũ. Hệ thống đang thử lại.`}
    />
  )
}
