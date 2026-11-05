import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppLayout } from '../../components/layout/AppLayout'
import { HomePage } from '../../pages/HomePage'
import { LoginPage } from '../../pages/LoginPage'
import { RegisterPage } from '../../pages/RegisterPage'
import { apiGet } from '../../services/apiClient'
import { alice, PASSWORD, upStatus } from '../../test/fixtures'
import {
  csrfEndpoint,
  deleteCookie,
  jsonResponse,
  noContent,
  noSession,
  problemResponse,
  signedIn,
  stubApi,
} from '../../test/http'
import { AuthProvider } from './AuthProvider'
import { RequireAuth } from './RequireAuth'

const CURRENT_USER = 'GET /api/v1/users/me'
const LOGIN = 'POST /api/v1/auth/login'

type Handlers = Parameters<typeof stubApi>[0][string]

function backend({ currentUser = noSession() }: { currentUser?: Handlers } = {}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    [CURRENT_USER]: currentUser,
    [LOGIN]: signedIn(alice),
    'POST /api/v1/auth/register': signedIn(alice, 201),
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      return noContent()
    },
    'GET /api/v1/wallet': () => problemResponse(401, 'SESSION_EXPIRED'),
  })
}

/** A response that the test sends later. */
function deferred() {
  let respond: (response: Response) => void = () => undefined
  const handler = () => new Promise<Response>((resolve) => (respond = resolve))
  return { handler, respond: (response: Response) => act(async () => respond(response)) }
}

/** Stands in for the first real protected page (the profile, W2-14). */
function Vault() {
  return <h1>Coin vault</h1>
}

/** Records every location the router shows, to prove that no redirect happened in between. */
function LocationSpy({ visited }: { visited: string[] }) {
  const location = useLocation()
  useEffect(() => {
    visited.push(`${location.pathname}${location.search}${location.hash}`)
  }, [location, visited])
  return null
}

/** The application's pages and layout, with one protected page, as after a full page load at `path`. */
function renderAt(path: string) {
  const visited: string[] = []
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <AppLayout>
          <LocationSpy visited={visited} />
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route element={<RequireAuth />}>
              <Route path="/vault" element={<Vault />} />
            </Route>
          </Routes>
        </AppLayout>
      </AuthProvider>
    </MemoryRouter>,
  )
  return { visited, current: () => visited[visited.length - 1] }
}

async function signIn() {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  return user
}

const vault = () => screen.queryByRole('heading', { name: 'Coin vault' })
const header = () => screen.getByRole('navigation', { name: 'Account' })

describe('RequireAuth', () => {
  it('sends anonymous users to the login page and never renders the protected page', async () => {
    backend()
    const router = renderAt('/vault?tab=coins#top')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Please sign in to open this page.')
    expect(router.current()).toBe('/login?returnTo=%2Fvault%3Ftab%3Dcoins%23top')
    expect(router.visited).toEqual(['/vault?tab=coins#top', '/login?returnTo=%2Fvault%3Ftab%3Dcoins%23top'])
    expect(vault()).not.toBeInTheDocument()
  })

  it('returns to the requested page, with its query and fragment, after signing in', async () => {
    const api = backend()
    const router = renderAt('/vault?tab=coins#top')
    await screen.findByRole('heading', { name: 'Sign in' })

    await signIn()

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
    expect(router.current()).toBe('/vault?tab=coins#top')
    expect(within(header()).getByText('Alice')).toBeInTheDocument()
    expect(api.requestsTo(LOGIN)).toHaveLength(1)
  })

  it('keeps the requested page when the user creates an account instead', async () => {
    backend()
    const router = renderAt('/vault')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('link', { name: 'Create an account' }))
    expect(router.current()).toBe('/register?returnTo=%2Fvault')
    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Display name'), 'Alice')
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByLabelText(/at least 18 years old/))
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
    expect(router.current()).toBe('/vault')
  })

  it('ignores a return address that leads to another site', async () => {
    backend()
    const router = renderAt(`/login?returnTo=${encodeURIComponent('//evil.example/vault')}`)
    await screen.findByRole('heading', { name: 'Sign in' })
    // Nothing internal was requested, so no "sign in to open this page" hint either.
    expect(screen.queryByText('Please sign in to open this page.')).not.toBeInTheDocument()

    await signIn()

    expect(await screen.findByRole('region', { name: 'Your account' })).toBeInTheDocument()
    expect(router.current()).toBe('/')
  })

  it('shows the protected page after a reload with a valid session, without a detour or flicker', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })
    const router = renderAt('/vault')

    // While the server is asked: neither the page nor the login form.
    expect(screen.getByRole('heading', { name: 'One moment' })).toBeInTheDocument()
    expect(screen.getByText('Checking your sign-in status…').parentElement).toHaveAttribute('aria-busy', 'true')
    expect(vault()).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()

    await check.respond(jsonResponse(alice))

    expect(vault()).toBeInTheDocument()
    expect(screen.queryByText(/Checking/)).not.toBeInTheDocument()
    expect(router.visited).toEqual(['/vault'])
  })

  it('explains an ended session on the login page and returns afterwards', async () => {
    backend({ currentUser: () => problemResponse(401, 'SESSION_EXPIRED') })
    const router = renderAt('/vault')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Sign in again to continue.')
    expect(router.current()).toBe('/login?returnTo=%2Fvault')
    expect(vault()).not.toBeInTheDocument()

    await signIn()

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
  })

  it('does not treat an unreachable server as signed out, and shows the page once the check succeeds', async () => {
    const api = backend({
      currentUser: [() => Promise.reject(new TypeError('Failed to fetch')), () => jsonResponse(alice)],
    })
    const router = renderAt('/vault')

    const card = await screen.findByRole('region', { name: 'Sign-in status unavailable' })
    expect(within(card).getByText(/could not be checked/)).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login?returnTo=%2Fvault')
    expect(vault()).not.toBeInTheDocument()
    expect(router.visited).toEqual(['/vault'])

    await userEvent.setup().click(within(card).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
    expect(api.requestsTo(CURRENT_USER)).toHaveLength(2)
    expect(router.visited).toEqual(['/vault'])
  })

  it('treats a server error like an unreachable server', async () => {
    backend({ currentUser: () => problemResponse(500, 'INTERNAL_ERROR') })
    renderAt('/vault')

    expect(await screen.findByRole('region', { name: 'Sign-in status unavailable' })).toBeInTheDocument()
    expect(vault()).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('leaves the page for the login page when the session ends during use, and comes back', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    const router = renderAt('/vault')
    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()

    // Any later request of the page, answered with 401 SESSION_EXPIRED.
    await act(async () => {
      await apiGet('/api/v1/wallet').catch(() => undefined)
    })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended.')
    expect(router.current()).toBe('/login?returnTo=%2Fvault')
    expect(vault()).not.toBeInTheDocument()
    expect(within(header()).queryByText('Alice')).not.toBeInTheDocument()

    await signIn()

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
  })

  it('goes to the home page, not the login page, when the user signs out on a protected page', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    const router = renderAt('/vault')
    await screen.findByRole('heading', { name: 'Coin vault' })

    await userEvent.setup().click(within(header()).getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText(/You are signed out\./)).toBeInTheDocument()
    expect(router.current()).toBe('/')
    expect(vault()).not.toBeInTheDocument()
  })

  it('sends a signed-in user from the login page straight to the requested page', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    const router = renderAt('/login?returnTo=%2Fvault')

    expect(await screen.findByRole('heading', { name: 'Coin vault' })).toBeInTheDocument()
    expect(router.current()).toBe('/vault')
  })
})
