import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { apiGet } from '../../services/apiClient'
import { alice, PASSWORD, upStatus } from '../../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, problemResponse, signedIn, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const LOGOUT = 'POST /api/v1/auth/logout'

function backend(logout: Parameters<typeof stubApi>[0][string] = () => {
  deleteCookie('XSRF-TOKEN')
  return noContent()
}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'POST /api/v1/auth/login': signedIn(alice),
    'GET /api/v1/wallet': () => problemResponse(401, 'SESSION_EXPIRED'),
    [LOGOUT]: logout,
  })
}

async function signIn() {
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('link', { name: 'Sign in' }))
  await user.type(screen.getByLabelText('Email'), 'alice@example.com')
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  await within(await screen.findByRole('region', { name: 'Your account' })).findByText('alice@example.com')
  return user
}

function accountPanel() {
  return screen.getByRole('region', { name: 'Your account' })
}

describe('Authentication state', () => {
  it('is unknown after a page load, not "signed out", and asks the server nothing about the user', async () => {
    const api = backend()

    renderApp('/')

    expect(within(accountPanel()).getByText(/could not be confirmed/)).toBeInTheDocument()
    expect(screen.queryByText('You are signed out.')).not.toBeInTheDocument()
    const header = screen.getByRole('navigation', { name: 'Account' })
    expect(within(header).getByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    // The platform status still loads; there is no request for the current user (GET /users/me is planned).
    expect(await screen.findByText('0.1.0-SNAPSHOT')).toBeInTheDocument()
    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual(['GET /api/v1/system/status'])
  })

  it('can end a possibly existing session while the state is unknown', async () => {
    const api = backend()
    renderApp('/')

    await userEvent.setup().click(within(accountPanel()).getByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(api.requestsTo(LOGOUT)[0]?.headers['X-XSRF-TOKEN']).toBe('csrf-1')
  })

  it('signs out from the header, deletes the in-memory user and renews the CSRF token', async () => {
    const api = backend()
    renderApp('/')
    const user = await signIn()

    await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(screen.queryByText('alice@example.com')).not.toBeInTheDocument()
    // Tokens: 1 for login, 2 after login, 3 after logout (each operation deletes the previous one).
    expect(api.requestsTo(LOGOUT)[0]?.headers['X-XSRF-TOKEN']).toBe('csrf-2')
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(3)
  })

  it('keeps the user signed in when logout fails', async () => {
    backend(() => Promise.reject(new TypeError('Failed to fetch')))
    renderApp('/')
    const user = await signIn()

    await user.click(within(accountPanel()).getByRole('button', { name: 'Sign out' }))

    expect(await within(accountPanel()).findByRole('alert')).toHaveTextContent('The server could not be reached.')
    expect(within(accountPanel()).getByText('Alice')).toBeInTheDocument()
  })

  it('is lost on a page reload: the new page does not claim either state', async () => {
    backend()
    const first = renderApp('/')
    await signIn()
    first.unmount()

    renderApp('/')

    expect(within(accountPanel()).getByText(/could not be confirmed/)).toBeInTheDocument()
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  it('goes to the login page with an explanation when the server reports 401 SESSION_EXPIRED', async () => {
    backend()
    renderApp('/')
    await signIn()

    // Any request of a later feature, e.g. a protected one.
    await act(async () => {
      await apiGet('/api/v1/wallet').catch(() => undefined)
    })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session has ended. Sign in again to continue.')
    expect(within(screen.getByRole('navigation', { name: 'Account' })).queryByText('Alice')).not.toBeInTheDocument()
  })

  it('shows a page for unknown paths', () => {
    backend()
    renderApp('/nowhere')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
