import { Navigate, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import PassengerHome from './pages/PassengerHome'
import TripDetailPage from './pages/TripDetailPage'
import DriverHome from './pages/DriverHome'
import DemoOverview from './pages/DemoOverview'
import { loadSession, routeForRole } from './auth/session'
import type { Role } from './auth/session'

/** Guard: chưa đăng nhập → /login; role không khớp route → về home của role mình. */
function Guarded({ role, children }: { role: Role; children: ReactElement }): ReactElement {
  const session = loadSession()
  if (!session) return <Navigate to="/login" replace />
  if (session.user.role !== role) return <Navigate to={routeForRole(session.user.role)} replace />
  return children
}

/** T29 — demo overview: mọi role đã đăng nhập đều xem được; tập trip lọc theo quyền qua GET /api/trips. */
function Authed({ children }: { children: ReactElement }): ReactElement {
  const session = loadSession()
  if (!session) return <Navigate to="/login" replace />
  return children
}

/** N34: người đã đăng nhập (session chưa hết hạn) không ở lại /login hay /register. */
function RedirectIfAuthed({ children }: { children: ReactElement }): ReactElement {
  const session = loadSession()
  if (session) return <Navigate to={routeForRole(session.user.role)} replace />
  return children
}

export default function App(): ReactElement {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route
        path="/login"
        element={
          <RedirectIfAuthed>
            <LoginPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        path="/register"
        element={
          <RedirectIfAuthed>
            <RegisterPage />
          </RedirectIfAuthed>
        }
      />
      <Route
        path="/passenger"
        element={
          <Guarded role="PASSENGER">
            <PassengerHome />
          </Guarded>
        }
      />
      {/* T27 — chi tiết chuyến hành khách. */}
      <Route
        path="/passenger/trips/:tripId"
        element={
          <Guarded role="PASSENGER">
            <TripDetailPage />
          </Guarded>
        }
      />
      <Route
        path="/driver"
        element={
          <Guarded role="DRIVER">
            <DriverHome />
          </Guarded>
        }
      />
      {/* T29 — màn hình demo overview (spec §14). */}
      <Route
        path="/demo"
        element={
          <Authed>
            <DemoOverview />
          </Authed>
        }
      />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
