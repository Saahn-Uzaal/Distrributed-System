// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import LoginPage from './LoginPage'
import { _resetAuthGenerationForTests } from '../auth/generation'

const USER = { id: 'u1', email: 'a@x.com', fullName: 'A B', role: 'PASSENGER' as const }

function fakeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body)
  } as unknown as Response
}

/** Hiện pathname hiện tại để test quan sát navigation. */
function LocationProbe(): React.JSX.Element {
  const loc = useLocation()
  return <div data-testid="loc">{loc.pathname}</div>
}

beforeEach(() => {
  sessionStorage.clear()
  _resetAuthGenerationForTests()
})
afterEach(() => {
  cleanup() // DOM giữa các test phải sạch (query document-level dễ dính node cũ).
  vi.unstubAllGlobals()
})

describe('LoginPage — F16: login cũ hoàn tất muộn không ghi đè phiên mới', () => {
  it('hai luồng chồng nhau (generation guard): phiên của luồng bắt đầu sau thắng', async () => {
    let fetchCalls = 0
    vi.stubGlobal(
      'fetch',
      async (_url: unknown, init?: RequestInit) => {
        fetchCalls++
        const body = JSON.parse(String(init?.body)) as { email: string }
        if (body.email === 'first@x.com') {
          // Login #1: chậm ~80 ms — vẫn đang chờ khi login #2 bắt đầu.
          await new Promise((r) => setTimeout(r, 80))
          return fakeResponse(200, { accessToken: 'token-A', expiresIn: 3600, user: USER })
        }
        return fakeResponse(200, { accessToken: 'token-B', expiresIn: 3600, user: USER })
      }
    )

    // Hai router ĐỘC LẬP để instance A không bị unmount khi B navigate.
    const { container } = render(
      <>
        <div id="a">
          <MemoryRouter initialEntries={['/login']}>
            <LoginPage />
          </MemoryRouter>
        </div>
        <div id="b">
          <MemoryRouter initialEntries={['/login']}>
            <LocationProbe />
            <LoginPage />
          </MemoryRouter>
        </div>
      </>
    )

    // Login #1 (chậm) — bắt đầu trước.
    const inputsA = container.querySelectorAll('#a input')
    fireEvent.change(inputsA[0], { target: { value: 'first@x.com' } })
    fireEvent.change(inputsA[1], { target: { value: 'secret123' } })
    const buttonA = Array.from(container.querySelectorAll('#a button')).find((b) => b.textContent === 'Đăng nhập')!
    fireEvent.click(buttonA)

    // Đợi để chắc chắn #1 đã bump generation và fetch đang treo.
    await new Promise((r) => setTimeout(r, 30))

    // Login #2 (nhanh) — bắt đầu sau.
    const inputsB = container.querySelectorAll('#b input')
    fireEvent.change(inputsB[0], { target: { value: 'second@x.com' } })
    fireEvent.change(inputsB[1], { target: { value: 'secret456' } })
    const buttonB = Array.from(container.querySelectorAll('#b button')).find((b) => b.textContent === 'Đăng nhập')!
    fireEvent.click(buttonB)

    // #2 hoàn tất trước -> session là token-B.
    await waitFor(() => expect(sessionStorage.getItem('ridehailing.session')).not.toBeNull())
    const stored = JSON.parse(sessionStorage.getItem('ridehailing.session')!) as { token: string }
    expect(stored.token).toBe('token-B')

    // #1 hoàn tất muộn -> generation đã đổi -> KHÔNG ghi đè.
    await waitFor(() => expect(fetchCalls).toBe(2))
    const storedAfter = JSON.parse(sessionStorage.getItem('ridehailing.session')!) as { token: string }
    expect(storedAfter.token).toBe('token-B')

    // #2 navigate về home của role (router B).
    expect(screen.getByTestId('loc').textContent).toBe('/passenger')
  })

  it('unmount trong lúc chờ -> fetch bị abort, không lưu session', async () => {
    let capturedSignal: AbortSignal | null = null
    vi.stubGlobal(
      'fetch',
      (_url: unknown, init?: RequestInit) => {
        capturedSignal = init?.signal ?? null
        return new Promise<Response>((_res, rej) => {
          const signal = init?.signal!
          if (signal.aborted) return void rej(new DOMException('Aborted', 'AbortError'))
          signal.addEventListener('abort', () => rej(new DOMException('Aborted', 'AbortError')))
        })
      }
    )

    const { container, unmount } = render(
      <MemoryRouter initialEntries={['/login']}>
        <LoginPage />
      </MemoryRouter>
    )

    const inputs = container.querySelectorAll('input')
    fireEvent.change(inputs[0], { target: { value: 'first@x.com' } })
    fireEvent.change(inputs[1], { target: { value: 'secret123' } })
    const button = Array.from(container.querySelectorAll('button')).find((b) => b.textContent === 'Đăng nhập')!
    fireEvent.click(button)

    // Đợi antd validation + onFinish gọi fetch (signal được ghi lại).
    await waitFor(() => expect(capturedSignal).not.toBeNull())
    unmount()

    // Sau khi fetch "hoàn tất" (đã abort), không có session nào được lưu.
    await waitFor(() => expect(capturedSignal!.aborted).toBe(true))
    await new Promise((r) => setTimeout(r, 50))
    expect(sessionStorage.getItem('ridehailing.session')).toBeNull()
  })
})
