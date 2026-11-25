import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { admin, alice, PASSWORD, startingWallet, upStatus } from '../../test/fixtures'
import { csrfEndpoint, jsonResponse, noSession, problemResponse, signedIn, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const CURRENT_USER = 'GET /api/v1/users/me'
const LOGIN = 'POST /api/v1/auth/login'
/** The first page of the wallet history, which only the wallet page requests (W5-05). */
const HISTORY = 'GET /api/v1/wallet/transactions?limit=20'

type Handlers = Parameters<typeof stubApi>[0][string]

/** No roulette route: a roulette request would fail the test ("Unexpected request"). */
function backend({ currentUser = noSession(), login = signedIn(alice) }: { currentUser?: Handlers; login?: Handlers } = {}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    [CURRENT_USER]: currentUser,
    [LOGIN]: login,
    'GET /api/v1/wallet': () => jsonResponse(startingWallet),
    [HISTORY]: () => jsonResponse({ items: [], nextCursor: null }),
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

/** Path, title, the page's own content, and the requests the page itself sends (besides the shared ones). */
const pages = [
  ['/roulette', 'European roulette', /Place your bets/, []],
  ['/wallet', 'Wallet', /No entries yet/, [HISTORY]],
] as const

const notice = () => screen.queryByRole('heading', { name: 'For player accounts only' })

describe.each(pages)('PlayerOnly on %s', (path, title, content, ownRequests) => {
  const page = () => screen.queryByRole('heading', { level: 1, name: title })

  it('shows the page to a player with the shared balance, without any roulette request', async () => {
    const api = backend({ currentUser: () => jsonResponse(alice) })
    renderApp(path)

    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(await screen.findByText(content)).toBeInTheDocument()
    expect(notice()).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
    expect(api.requests.map((request) => `${request.method} ${request.path}`).sort()).toEqual(
      [CURRENT_USER, 'GET /api/v1/wallet', ...ownRequests].sort(),
    )
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
  it('roulette states honestly that it cannot be played yet: bets can be placed, but no spin control and no result', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    renderApp('/roulette')

    const table = await screen.findByRole('region', { name: 'Place your bets' })
    expect(within(table).getByText(/Spinning is not switched on yet/)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Refresh' })).toBeInTheDocument()
    // W5-06 adds the table, the chips and the slip, but no spin or confirmation control (W5-07).
    expect(screen.queryByRole('button', { name: /spin|confirm/i })).not.toBeInTheDocument()
    // No bets and no game result: the slip is empty, and the balances are the server's.
    const slip = screen.getByRole('region', { name: 'Bet slip' })
    expect(within(slip).getByText(/No bets yet/)).toBeInTheDocument()
    expect(within(slip).getByText(/^Your balance:/)).toHaveTextContent('Your balance: 1,000 coins')
    expect(within(screen.getByRole('region', { name: 'Your balance' })).getByText('1,000 coins')).toBeInTheDocument()
    expect(screen.queryByText(/win|payout/i)).not.toBeInTheDocument()
  })

  it('the wallet shows the server balance and only the server’s entries: none yet, so no invented transactions', async () => {
    backend({ currentUser: () => jsonResponse(alice) })
    renderApp('/wallet')

    const history = await screen.findByRole('region', { name: 'Transaction history' })
    expect(await within(history).findByText('No entries yet.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,000 coins')).toBeInTheDocument()
    expect(screen.getAllByText(/\d[\d,]* coins/).map((element) => element.textContent)).toEqual(['1,000 coins', '1,000 coins'])
  })
})
