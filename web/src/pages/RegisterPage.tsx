import { useState } from 'react'
import type { ReactElement } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Select, Typography } from 'antd'
import { api, ApiError } from '../api/client'
import { saveSession, routeForRole } from '../auth/session'
import { validateFullName, validatePassword } from '../auth/validation'

export default function RegisterPage(): ReactElement {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function onFinish(values: {
    email: string
    password: string
    fullName: string
    role: 'PASSENGER' | 'DRIVER'
  }): Promise<void> {
    setError(null)
    setLoading(true)
    try {
      await api.register(values)
      // register không trả token (contract §3.1) → đăng nhập ngay sau đó
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
    <Card title="Đăng ký" style={{ maxWidth: 420, margin: '48px auto' }}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={onFinish}>
        <Form.Item label="Email" name="email" rules={[{ required: true, type: 'email' }]}>
          <Input />
        </Form.Item>
        {/* R06 fix: validation khớp contract §3.1 — password theo BYTE UTF-8 (≤72),
            fullName kiểm tra sau trim (2–100). */}
        <Form.Item
          label="Họ tên (2–100 ký tự, bỏ khoảng trắng hai đầu)"
          name="fullName"
          rules={[
            { required: true },
            {
              validator: (_rule, value?: string) => {
                const err = validateFullName(value ?? '')
                return err ? Promise.reject(new Error(err)) : Promise.resolve()
              }
            }
          ]}
        >
          <Input />
        </Form.Item>
        <Form.Item
          label="Mật khẩu (tối thiểu 8 ký tự, tối đa 72 byte UTF-8)"
          name="password"
          rules={[
            { required: true },
            {
              validator: (_rule, value?: string) => {
                const err = validatePassword(value ?? '')
                return err ? Promise.reject(new Error(err)) : Promise.resolve()
              }
            }
          ]}
        >
          <Input.Password />
        </Form.Item>
        <Form.Item label="Vai trò" name="role" initialValue="PASSENGER" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'PASSENGER', label: 'Hành khách' },
              { value: 'DRIVER', label: 'Tài xế' }
            ]}
          />
        </Form.Item>
        <Button htmlType="submit" type="primary" block loading={loading}>
          Đăng ký
        </Button>
      </Form>
      <Typography.Text style={{ marginTop: 16 }}>
        Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
      </Typography.Text>
    </Card>
  )
}
