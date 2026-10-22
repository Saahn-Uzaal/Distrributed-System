import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Select, Typography } from 'antd'
import { api, ApiError } from '../api/client'
import { saveSession, routeForRole } from '../auth/session'
import { bumpAuthGeneration, getAuthGeneration } from '../auth/generation'
import { validateEmail, validateFullName, validatePassword } from '../auth/validation'

export default function RegisterPage(): ReactElement {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  // F16: continuation của register/login cũ không được ghi đè phiên mới.
  const mountedRef = useRef(true)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
  }, [])

  async function onFinish(values: {
    email: string
    password: string
    fullName: string
    role: 'PASSENGER' | 'DRIVER'
  }): Promise<void> {
    setError(null)
    setLoading(true)
    const gen = bumpAuthGeneration()
    const controller = new AbortController()
    abortRef.current = controller
    try {
      await api.register(values, controller.signal)
      // register không trả token (contract §3.1) → đăng nhập ngay sau đó
      try {
        const res = await api.login(values.email, values.password, controller.signal)
        // F16: chỉ lưu phiên + navigate nếu không có luồng auth mới hơn và component còn mounted.
        if (!mountedRef.current || gen !== getAuthGeneration()) return
        saveSession(res.accessToken, res.user, res.expiresIn)
        navigate(routeForRole(res.user.role))
      } catch (e2) {
        // F18: register 201 nhưng login lỗi -> dẫn về trang đăng nhập kèm thông báo
        // (truyền qua location.state vì component này unmount sau khi navigate).
        if (!mountedRef.current || gen !== getAuthGeneration()) return
        navigate('/login', { state: { notice: 'Đăng ký thành công, vui lòng đăng nhập.' } })
      }
    } catch (e) {
      if (!mountedRef.current) return
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Card title="Đăng ký" style={{ maxWidth: 420, margin: '48px auto' }}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
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
        {/* R06 + F17 fix: validation khớp contract §3.1 — password theo BYTE UTF-8 (≤72),
            không toàn whitespace; fullName kiểm tra sau trim (Java semantics). */}
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
