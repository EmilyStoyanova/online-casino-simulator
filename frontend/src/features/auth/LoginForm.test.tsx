import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { alice, PASSWORD, upStatus } from '../../test/fixtures'
import { csrfEndpoint, jsonResponse, noSession, problemResponse, signedIn, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const LOGIN = 'POST /api/v1/auth/login'

function backend(login: Parameters<typeof stubApi>[0][string]) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/users/me': noSession(),
    [LOGIN]: login,
  })
}

async function signIn(password = PASSWORD) {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), 'alice@example.com')
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  return user
}

describe('Login', () => {
  it('signs in and shows the user', async () => {
    const api = backend(signedIn(alice))
    renderApp('/login')

    await signIn()

    const account = await screen.findByRole('region', { name: 'Your account' })
    expect(within(account).getByText('Alice')).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('button', { name: 'Sign out' }))
      .toBeInTheDocument()
    expect(api.requestsTo(LOGIN)).toEqual([
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
        body: { email: 'alice@example.com', password: PASSWORD },
      }),
    ])
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(2)
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
  })

  it('requires both fields before sending', async () => {
    const api = backend(signedIn(alice))
    renderApp('/login')

    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign in' }))

    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('Enter your email address.')
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription('Enter your password.')
    expect(api.requestsTo(LOGIN)).toHaveLength(0)
  })

  it('reports wrong credentials and disabled accounts with one generic message', async () => {
    backend(() => problemResponse(401, 'INVALID_CREDENTIALS'))
    renderApp('/login')

    await signIn('wrong-password-123')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The email or password is incorrect. Accounts that have been disabled cannot sign in.',
    )
    // Still signed out, still on the login page, ready for another attempt.
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
    expect(within(screen.getByRole('navigation', { name: 'Account' })).queryByRole('button', { name: 'Sign out' }))
      .not.toBeInTheDocument()
  })

  it('shows the waiting time of 429 from Retry-After and disables sign-in meanwhile', async () => {
    backend(() => problemResponse(429, 'TOO_MANY_ATTEMPTS', {}, { 'Retry-After': '850' }))
    renderApp('/login')

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many failed sign-in attempts from this network. Try again in 15 minutes.',
    )
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
  })

  it('explains a server that cannot be reached', async () => {
    const api = backend(() => Promise.reject(new TypeError('Failed to fetch')))
    renderApp('/login')

    await signIn()

    expect(await screen.findByRole('alert')).toHaveTextContent('The server could not be reached.')
    expect(api.requestsTo(LOGIN)).toHaveLength(1)
  })

  it('shows the pending request', async () => {
    let respond: (response: Response) => void = () => undefined
    backend(() => new Promise<Response>((resolve) => (respond = resolve)))
    renderApp('/login')

    await signIn()

    expect(await screen.findByRole('button', { name: 'Signing in…' })).toBeDisabled()
    respond(problemResponse(401, 'INVALID_CREDENTIALS'))
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('lets the password be shown and hidden', async () => {
    backend(signedIn(alice))
    renderApp('/login')
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })
})
