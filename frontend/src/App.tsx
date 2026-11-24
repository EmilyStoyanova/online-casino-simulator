import { Route, Routes } from 'react-router'
import { AppLayout } from './components/layout/AppLayout'
import { AuthProvider } from './features/auth/AuthProvider'
import { PlayerOnly } from './features/auth/PlayerOnly'
import { RequireAuth } from './features/auth/RequireAuth'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'
import { RoulettePage } from './pages/RoulettePage'
import { WalletPage } from './pages/WalletPage'

/**
 * The application inside a router (`BrowserRouter` in `main.tsx`, `MemoryRouter` in tests). Pages that
 * need a signed-in user go under `<Route element={<RequireAuth />}>`; pages only players can use go
 * one level deeper, under `<Route element={<PlayerOnly />}>`.
 */
export function App() {
  return (
    <AuthProvider>
      <AppLayout>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route element={<RequireAuth />}>
            <Route path="/profile" element={<ProfilePage />} />
            <Route element={<PlayerOnly />}>
              <Route path="/roulette" element={<RoulettePage />} />
              <Route path="/wallet" element={<WalletPage />} />
            </Route>
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </AppLayout>
    </AuthProvider>
  )
}
