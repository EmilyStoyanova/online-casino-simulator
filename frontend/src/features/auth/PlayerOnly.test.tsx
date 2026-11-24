import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { admin, alice, PASSWORD, upStatus } from '../../test/fixtures'
import { csrfEndpoint, jsonResponse, noSession, problemResponse, signedIn, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const CURRENT_USER = 'GET /api/v1/users/me'
const LOGIN = 'POST /api/v1/auth/login'

type Handlers = Parameters<typeof stubApi>[0][string]

/** No wallet or roulette route: a request to one of them would fail the test ("Unexpected request"). */
function backend({ currentUser = noSession(), login = signedIn(alice) }: { currentUser?: Handlers; login?: Handlers } = {}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    [CURRENT_USER]: currentUser,
    [LOGIN]: login,
  })
}

function deferred() {
  let respond: (response: Response) => void = () => undefined
  const handler = () => new Promise<Response>((resolve) => (respond = resolve))
  return { handler, respond: (response: Response) => act(async () => respond(response)) }
}

async function signIn(email: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

const pages = [
  ['/roulette', 'European roulette'],
  ['/wallet', 'Wallet'],
] as const

const notice = () => screen.queryByRole('heading', { name: 'For player accounts only' })

describe.each(pages)('PlayerOnly on %s', (path, title) => {
  const page = () => screen.queryByRole('heading', { level: 1, name: title })

  it('shows the page to a player, without any wallet or roulette request', async () => {
    const api = backend({ currentUser: () => jsonResponse(alice) })
    renderApp(path)

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(screen.getByText(/being built/)).toBeInTheDocument()
    expect(notice()).not.toBeInTheDocument()
    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual([CURRENT_USER])
  })

  it('shows an administrator an explanation with a way back, never the page, and sends nothing more', async () => {
    const api = backend({ currentUser: () => jsonResponse(admin) })
    renderApp(path)

    const card = await screen.findByRole('region', { name: 'For player accounts only' })
    expect(within(card).getByText(/Administrator accounts have no wallet/)).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Back to the home page' })).toHaveAttribute('href', '/')
    expect(page()).not.toBeInTheDocument()
    expect(api.requests.map((request) => `${request.method} ${request.path}`)).toEqual([CURRENT_USER])
  })

  it('sends a visitor to the login page with returnTo and comes back after a player signs in', async () => {
    backend()
    renderApp(path)

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Please sign in to open this page.')
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute(
      'href',
      `/register?returnTo=${encodeURIComponent(path)}`,
    )
    expect(page()).not.toBeInTheDocument()

    await signIn('alice@example.com')

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
  })

  it('shows the explanation when an administrator signs in through the redirect', async () => {
    backend({ login: signedIn(admin) })
    renderApp(path)

    await signIn('admin@example.com')

    expect(await screen.findByRole('heading', { name: 'For player accounts only' })).toBeInTheDocument()
    expect(page()).not.toBeInTheDocument()
  })

  it('shows neither the page nor the explanation while the session is being checked', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })
    renderApp(path)

    expect(screen.getByRole('heading', { name: 'One moment' })).toBeInTheDocument()
    expect(page()).not.toBeInTheDocument()
    expect(notice()).not.toBeInTheDocument()

    await check.respond(jsonResponse(alice))

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
  })

  it('shows neither the page nor the explanation when the session cannot be checked', async () => {
    backend({ currentUser: () => problemResponse(500, 'INTERNAL_ERROR') })
    renderApp(path)

    const card = await screen.findByRole('region', { name: 'Sign-in status unavailable' })
    expect(within(card).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      `/login?returnTo=${encodeURIComponent(path)}`,
    )
    expect(page()).not.toBeInTheDocument()
    expect(notice()).not.toBeInTheDocument()
  })
})

describe('Placeholder pages', () => {
  it('state honestly that roulette cannot be played yet and show no game result or balance', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    renderApp('/roulette')

    const progress = await screen.findByRole('region', { name: 'The roulette table is being built' })
    expect(within(progress).getByText(/You cannot place bets yet/)).toBeInTheDocument()
    // No betting control exists yet: the only button is the header's "Sign out".
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual(['Sign out'])
    expect(screen.queryByText(/\d[\d,]* coins/)).not.toBeInTheDocument()
  })

  it('show no invented wallet balance or transactions', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    renderApp('/wallet')

    const progress = await screen.findByRole('region', { name: 'Your wallet page is being built' })
    expect(within(progress).getByText(/not shown here yet/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText(/\d[\d,]* coins/)).not.toBeInTheDocument()
  })
})
