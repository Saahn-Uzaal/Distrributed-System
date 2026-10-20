import { useState } from 'react'
import type { ReactElement } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Typography } from 'antd'
import { api, ApiError } from '../api/client'
import { saveSession, routeForRole } from '../auth/session'

export default function LoginPage(): ReactElement {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function onFinish(values: { email: string; password: string }): Promise<void> {
    setError(null)
    setLoading(true)
    try {
      const res = await api.login(values.email, values.password)
      saveSession(res.accessToken, res.user)
      navigate(routeForRole(res.user.role))
    } catch (e) {
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card title="Đăng nhập" style={{ maxWidth: 420, margin: '48px auto' }}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={onFinish}>
        <Form.Item label="Email" name="email" rules={[{ required: true, type: 'email' }]}>
          <Input />
        </Form.Item>
        <Form.Item label="Mật khẩu" name="password" rules={[{ required: true }]}>
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
