import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { alice, PASSWORD, upStatus } from '../../test/fixtures'
import { csrfEndpoint, jsonResponse, noSession, problemResponse, signedIn, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const REGISTER = 'POST /api/v1/auth/register'

function backend(register: Parameters<typeof stubApi>[0][string]) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/users/me': noSession(),
    [REGISTER]: register,
  })
}

async function fillIn(values: { email?: string; displayName?: string; password?: string; adult?: boolean } = {}) {
  const user = userEvent.setup()
  const { email = 'alice@example.com', displayName = 'Alice', password = PASSWORD, adult = true } = values
  if (email) await user.type(screen.getByLabelText('Email'), email)
  if (displayName) await user.type(screen.getByLabelText('Display name'), displayName)
  if (password) await user.type(screen.getByLabelText('Password'), password)
  if (adult) await user.click(screen.getByLabelText(/at least 18 years old/))
  return user
}

describe('Registration', () => {
  it('creates the account, signs the user in and shows the account without another login', async () => {
    const api = backend(signedIn(alice, 201))
    renderApp('/register')
    expect(screen.getByText(/cannot be bought, withdrawn or exchanged/)).toBeInTheDocument()

    const user = await fillIn()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    // Back on the home page, signed in with the profile from the response.
    const account = await screen.findByRole('region', { name: 'Your account' })
    expect(within(account).getByText('Alice')).toBeInTheDocument()
    expect(within(account).getByText('alice@example.com')).toBeInTheDocument()
    expect(within(account).getByText('Player')).toBeInTheDocument()
    expect(within(account).getAllByText('2 November 2026')).toHaveLength(2)
    const header = screen.getByRole('navigation', { name: 'Account' })
    expect(within(header).getByText('Alice')).toBeInTheDocument()
    expect(within(header).getByRole('button', { name: 'Sign out' })).toBeInTheDocument()

    expect(api.requestsTo(REGISTER)).toEqual([
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' }),
        body: { email: 'alice@example.com', password: PASSWORD, displayName: 'Alice', adultConfirmed: true },
      }),
    ])
    // A new token after the server deleted the old one; nothing about the user is kept in storage.
    expect(api.requestsTo('GET /api/v1/auth/csrf')).toHaveLength(2)
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    expect(document.cookie).not.toContain(PASSWORD)
  })

  it('checks every field before sending and focuses the first error', async () => {
    const api = backend(signedIn(alice, 201))
    renderApp('/register')
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription('Enter your email address.')
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Email')).toHaveFocus()
    expect(screen.getByText('Enter a display name.')).toBeInTheDocument()
    expect(screen.getByText('Enter a password.')).toBeInTheDocument()
    expect(screen.getByText('Confirm that you are at least 18 years old.')).toBeInTheDocument()
    expect(api.requestsTo(REGISTER)).toHaveLength(0)
  })

  it('applies the password policy of the server', async () => {
    const api = backend(signedIn(alice, 201))
    renderApp('/register')

    const user = await fillIn({ password: 'short' })
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'ALICE@example.com')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(screen.getByText('Your password must not be your email address.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'é'.repeat(37))
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(screen.getByText(/Use at most 72 bytes/)).toBeInTheDocument()
    expect(api.requestsTo(REGISTER)).toHaveLength(0)
  })

  it('shows the field errors of the server next to the fields', async () => {
    backend(() =>
      problemResponse(400, 'VALIDATION_FAILED', {
        errors: [
          { field: 'displayName', message: 'must not contain control characters' },
          { field: 'password', message: 'must not equal the display name' },
        ],
      }),
    )
    renderApp('/register')

    const user = await fillIn()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Please correct the highlighted fields.')
    expect(screen.getByLabelText('Display name')).toHaveAccessibleDescription(
      expect.stringContaining('Must not contain control characters.'),
    )
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      expect.stringContaining('Must not equal the display name.'),
    )
    expect(screen.getByLabelText('Display name')).toHaveFocus()

    // Editing a field removes its error.
    await user.type(screen.getByLabelText('Display name'), 'x')
    expect(screen.queryByText('Must not contain control characters.')).not.toBeInTheDocument()
  })

  it('shows the rejection of a commonly used password, which only the server checks', async () => {
    const api = backend(() =>
      problemResponse(400, 'VALIDATION_FAILED', {
        errors: [{ field: 'password', message: 'must not be a commonly used password' }],
      }),
    )
    renderApp('/register')
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      expect.stringContaining('not a commonly used password'),
    )

    const user = await fillIn({ password: 'Password1234' })
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    // The list stays on the server, so the request is sent and its field error is shown.
    expect(api.requestsTo(REGISTER)).toHaveLength(1)
    expect(await screen.findByRole('alert')).toHaveTextContent('Please correct the highlighted fields.')
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      expect.stringContaining('Must not be a commonly used password.'),
    )
    expect(screen.getByLabelText('Password')).toHaveFocus()
  })

  it('reports an email that is already registered and links to sign in', async () => {
    backend(() => problemResponse(409, 'EMAIL_ALREADY_REGISTERED'))
    renderApp('/register')

    const user = await fillIn()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('An account with this email already exists.')).toBeInTheDocument()
    expect(within(screen.getByRole('alert')).getByRole('link', { name: 'Go to sign in' })).toHaveAttribute('href', '/login')
  })

  it('shows the waiting time of 429 and disables the form meanwhile', async () => {
    backend(() => problemResponse(429, 'TOO_MANY_ATTEMPTS', {}, { 'Retry-After': '3600' }))
    renderApp('/register')

    const user = await fillIn()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many registration attempts from this network. Try again in 60 minutes.',
    )
    expect(screen.getByRole('button', { name: 'Create account' })).toBeDisabled()
  })

  it('shows the pending request', async () => {
    let respond: (response: Response) => void = () => undefined
    backend(() => new Promise<Response>((resolve) => (respond = resolve)))
    renderApp('/register')

    const user = await fillIn()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    const pending = await screen.findByRole('button', { name: 'Creating account…' })
    expect(pending).toBeDisabled()
    expect(screen.getByLabelText('Email')).toBeDisabled()
    respond(problemResponse(500, 'INTERNAL_ERROR'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong on our side.')
    expect(screen.getByRole('button', { name: 'Create account' })).toBeEnabled()
  })
})
