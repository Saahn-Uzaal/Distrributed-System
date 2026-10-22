// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import RegisterPage from './RegisterPage'
import LoginPage from './LoginPage'

const USER = { id: 'u1', email: 'a@x.com', fullName: 'A B', role: 'PASSENGER' as const }

function fakeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('RegisterPage — F18: register 201 nhưng login lỗi -> báo + chuyển /login', () => {
  it('hiển thị "Đăng ký thành công, vui lòng đăng nhập." trên trang login', async () => {
    vi.stubGlobal(
      'fetch',
      async (url: unknown) => {
        const path = String(url)
        if (path.includes('/register')) return fakeResponse(201, { user: USER })
        // Login ngay sau register thất bại.
        return fakeResponse(401, { code: 'INVALID_CREDENTIALS', message: 'Sai thông tin đăng nhập.' })
      }
    )

    const { container } = render(
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </MemoryRouter>
    )

    // 3 input: email, fullName, password (role có initialValue PASSENGER).
    const inputs = container.querySelectorAll('input')
    fireEvent.change(inputs[0], { target: { value: 'reg@x.com' } })
    fireEvent.change(inputs[1], { target: { value: 'Test User' } })
    fireEvent.change(inputs[2], { target: { value: 'secret123' } })
    const button = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === 'Đăng ký')!
    fireEvent.click(button)

    // Sau navigate, LoginPage hiển thị thông báo (location.state.notice).
    await waitFor(() => expect(screen.queryByText('Đăng ký thành công, vui lòng đăng nhập.')).not.toBeNull())
  })
})
