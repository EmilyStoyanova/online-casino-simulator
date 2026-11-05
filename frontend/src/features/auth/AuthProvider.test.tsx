import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
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
import { renderApp } from '../../test/renderApp'

const LOGOUT = 'POST /api/v1/auth/logout'
const CURRENT_USER = 'GET /api/v1/users/me'

type Handlers = Parameters<typeof stubApi>[0][string]

function backend({
  currentUser = noSession(),
  logout = () => {
    deleteCookie('XSRF-TOKEN')
    return noContent()
  },
}: { currentUser?: Handlers; logout?: Handlers } = {}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    [CURRENT_USER]: currentUser,
    'POST /api/v1/auth/login': signedIn(alice),
    'GET /api/v1/wallet': () => problemResponse(401, 'SESSION_EXPIRED'),
    [LOGOUT]: logout,
  })
}

/** A response that the test sends later. */
function deferred() {
  let respond: (response: Response) => void = () => undefined
  const handler = () => new Promise<Response>((resolve) => (respond = resolve))
  return { handler, respond: (response: Response) => act(async () => respond(response)) }
}

async function signIn() {
  const user = userEvent.setup()
  await user.click(await within(header()).findByRole('link', { name: 'Sign in' }))
  await user.type(screen.getByLabelText('Email'), 'alice@example.com')
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  await within(await screen.findByRole('region', { name: 'Your account' })).findByText('alice@example.com')
  return user
}

function accountPanel() {
  return screen.getByRole('region', { name: 'Your account' })
}

function header() {
  return screen.getByRole('navigation', { name: 'Account' })
}

describe('Session restoration on start-up', () => {
  it('restores the signed-in user from the session cookie', async () => {
    const api = backend({ currentUser: () => jsonResponse({ ...alice, interests: ['PROBABILITY'] }) })

    renderApp('/')

    expect(await within(accountPanel()).findByText('alice@example.com')).toBeInTheDocument()
    expect(within(header()).getByText('Alice')).toBeInTheDocument()
    expect(within(header()).getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    // One request, sent with the cookies; the identity comes from the session, nothing is sent with it.
    expect(api.requestsTo(CURRENT_USER)).toHaveLength(1)
    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/users/me', expect.objectContaining({ credentials: 'include' }))
    expect(api.requestsTo(CURRENT_USER)[0]?.body).toBeUndefined()
    // A GET needs no CSRF token; nothing is kept in browser storage.
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(0)
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
  })

  it('shows that the status is being checked until the server answers', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })

    renderApp('/')

    expect(within(header()).getByText('Checking sign-in…')).toBeInTheDocument()
    expect(within(header()).queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument()
    expect(within(accountPanel()).getByText('Checking your sign-in status…')).toBeInTheDocument()
    expect(within(accountPanel()).getByText('Checking your sign-in status…').parentElement).toHaveAttribute('aria-busy', 'true')

    await check.respond(problemResponse(401, 'AUTHENTICATION_REQUIRED'))

    expect(within(accountPanel()).getByText(/You are signed out\./)).toBeInTheDocument()
    expect(within(header()).getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByText(/Checking/)).not.toBeInTheDocument()
  })

  it('shows the signed-out state for a confirmed 401 and stays on the page', async () => {
    backend()

    renderApp('/')

    expect(await within(accountPanel()).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(within(accountPanel()).getByRole('link', { name: 'Create account' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('explains an ended session without sending the user to the login page', async () => {
    backend({ currentUser: () => problemResponse(401, 'SESSION_EXPIRED') })

    renderApp('/')

    expect(await within(accountPanel()).findByText(/Your session has ended\./)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('does not assume a sign-out when the server cannot be reached, and checks again on request', async () => {
    const api = backend({
      currentUser: [() => Promise.reject(new TypeError('Failed to fetch')), () => jsonResponse(alice)],
    })
    renderApp('/')

    expect(await within(accountPanel()).findByText(/could not be checked/)).toBeInTheDocument()
    expect(screen.queryByText(/You are signed out/)).not.toBeInTheDocument()
    expect(within(accountPanel()).getByRole('link', { name: 'Sign in' })).toBeInTheDocument()

    await userEvent.setup().click(within(accountPanel()).getByRole('button', { name: 'Try again' }))

    expect(await within(accountPanel()).findByText('alice@example.com')).toBeInTheDocument()
    expect(api.requestsTo(CURRENT_USER)).toHaveLength(2)
  })

  it('does not assume a sign-out after a server error either', async () => {
    backend({ currentUser: () => problemResponse(500, 'INTERNAL_ERROR') })

    renderApp('/')

    expect(await within(accountPanel()).findByText(/could not be checked/)).toBeInTheDocument()
    expect(screen.queryByText(/You are signed out/)).not.toBeInTheDocument()
  })

  it('survives a page reload: the new page asks the server again', async () => {
    const api = backend({ currentUser: [noSession(), () => jsonResponse(alice)] })
    const first = renderApp('/')
    await signIn()
    first.unmount()

    renderApp('/')

    expect(await within(accountPanel()).findByText('alice@example.com')).toBeInTheDocument()
    expect(api.requestsTo(CURRENT_USER)).toHaveLength(2)
  })

  it('keeps a sign-in that finishes before a slow start-up check', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })
    renderApp('/login')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    const account = await screen.findByRole('region', { name: 'Your account' })
    expect(await within(account).findByText('alice@example.com')).toBeInTheDocument()

    // The check was sent before the sign-in, with the old (missing) session.
    await check.respond(problemResponse(401, 'AUTHENTICATION_REQUIRED'))

    expect(within(accountPanel()).getByText('alice@example.com')).toBeInTheDocument()
    expect(within(header()).getByText('Alice')).toBeInTheDocument()
  })

  it('keeps a sign-in when a slow start-up check reports the old session as ended', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })
    renderApp('/login')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await within(await screen.findByRole('region', { name: 'Your account' })).findByText('alice@example.com'))
      .toBeInTheDocument()

    // SESSION_EXPIRED concerns the cookie the check was sent with, not the new session.
    await check.respond(problemResponse(401, 'SESSION_EXPIRED'))

    expect(within(accountPanel()).getByText('alice@example.com')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Your session has ended/)).not.toBeInTheDocument()
  })
})

describe('Authentication state', () => {
  it('can end a possibly existing session while the state is unknown', async () => {
    const api = backend({ currentUser: () => Promise.reject(new TypeError('Failed to fetch')) })
    renderApp('/')

    await userEvent.setup().click(await within(accountPanel()).findByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(api.requestsTo(LOGOUT)[0]?.headers['X-XSRF-TOKEN']).toBe('csrf-1')
  })

  it('signs out from the header, deletes the in-memory user and renews the CSRF token', async () => {
    const api = backend()
    renderApp('/')
    const user = await signIn()

    await user.click(within(header()).getByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(screen.queryByText('alice@example.com')).not.toBeInTheDocument()
    // Tokens: 1 for login, 2 after login, 3 after logout (each operation deletes the previous one).
    expect(api.requestsTo(LOGOUT)[0]?.headers['X-XSRF-TOKEN']).toBe('csrf-2')
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(3)
  })

  it('keeps the user signed in when logout fails', async () => {
    backend({ logout: () => Promise.reject(new TypeError('Failed to fetch')) })
    renderApp('/')
    const user = await signIn()

    await user.click(within(accountPanel()).getByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByRole('alert')).toHaveTextContent('The server could not be reached.')
    expect(within(accountPanel()).getByText('Alice')).toBeInTheDocument()
  })

  it('goes to the login page with an explanation when the session ends while signed in', async () => {
    backend()
    renderApp('/')
    await signIn()

    // Any request of a later feature, e.g. a protected one.
    await act(async () => {
      await apiGet('/api/v1/wallet').catch(() => undefined)
    })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Sign in again to continue.')
    expect(within(header()).queryByText('Alice')).not.toBeInTheDocument()
  })

  it('shows a page for unknown paths', () => {
    backend()
    renderApp('/nowhere')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
