import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Typography } from 'antd'
import { api, ApiError } from '../api/client'
import { saveSession, routeForRole } from '../auth/session'
import { bumpAuthGeneration, getAuthGeneration } from '../auth/generation'
import { validateEmail, validateLoginPassword } from '../auth/validation'
import { errorHint } from '../ui/errorHint'

export default function LoginPage(): ReactElement {
  const navigate = useNavigate()
  const location = useLocation()
  // F18: thông báo từ RegisterPage (register 201 nhưng login lỗi) qua location.state.
  const notice = typeof (location.state as { notice?: string } | null)?.notice === 'string'
    ? (location.state as { notice: string }).notice
    : null
  const [error, setError] = useState<string | null>(null)
  // T27 — gợi ý tiếng Việt cho mã lỗi đã biết (vd RATE_LIMITED 429).
  const [hint, setHint] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  // F16: continuation của login cũ không được ghi đè phiên mới.
  const mountedRef = useRef(true)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
  }, [])

  async function onFinish(values: { email: string; password: string }): Promise<void> {
    setError(null)
    setHint(null)
    setLoading(true)
    const gen = bumpAuthGeneration()
    const controller = new AbortController()
    abortRef.current = controller
    try {
      const res = await api.login(values.email, values.password, controller.signal)
      // F16: chỉ lưu phiên + navigate nếu không có luồng auth mới hơn và component còn mounted.
      if (!mountedRef.current || gen !== getAuthGeneration()) return
      saveSession(res.accessToken, res.user, res.expiresIn)
      navigate(routeForRole(res.user.role))
    } catch (e) {
      if (!mountedRef.current) return
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
      setHint(e instanceof ApiError ? errorHint(e.code) : null)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card title="Đăng nhập" style={{ maxWidth: 420, margin: '48px auto' }}>
      {notice && <Alert type="success" message={notice} style={{ marginBottom: 16 }} />}
      {error && <Alert type="error" message={error} description={hint ?? undefined} style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={onFinish}>
        {/* F17: email khớp backend — không blank, ≤ 254 ký tự (định dạng do AntD kiểm tra). */}
        <Form.Item
          label="Email"
          name="email"
          rules={[
            { required: true, type: 'email' },
            {
              validator: (_rule, value?: string) => {
                const err = validateEmail(value ?? '')
                return err ? Promise.reject(new Error(err)) : Promise.resolve()
              }
            }
          ]}
        >
          <Input />
        </Form.Item>
        {/* F17: login phía backend chỉ enforce @NotBlank password (sai credentials -> 401). */}
        <Form.Item
          label="Mật khẩu"
          name="password"
          rules={[
            { required: true },
            {
              validator: (_rule, value?: string) => {
                const err = validateLoginPassword(value ?? '')
                return err ? Promise.reject(new Error(err)) : Promise.resolve()
              }
            }
          ]}
        >
          <Input.Password />
        </Form.Item>
        <Button htmlType="submit" type="primary" block loading={loading}>
          Đăng nhập
        </Button>
      </Form>
      <Typography.Text style={{ marginTop: 16 }}>
        Chưa có tài khoản? <Link to="/register">Đăng ký</Link>
      </Typography.Text>
    </Card>
  )
}
