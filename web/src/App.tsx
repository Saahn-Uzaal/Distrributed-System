import { Navigate, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import PassengerHome from './pages/PassengerHome'
import DriverHome from './pages/DriverHome'
import { loadSession, routeForRole } from './auth/session'
import type { Role } from './auth/session'

/** Guard: chưa đăng nhập → /login; role không khớp route → về home của role mình. */
function Guarded({ role, children }: { role: Role; children: ReactElement }): ReactElement {
  const session = loadSession()
  if (!session) return <Navigate to="/login" replace />
  if (session.user.role !== role) return <Navigate to={routeForRole(session.user.role)} replace />
  return children
}

export default function App(): ReactElement {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        path="/passenger"
        element={
          <Guarded role="PASSENGER">
            <PassengerHome />
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
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
