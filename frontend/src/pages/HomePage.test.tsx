import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { admin, alice, startingWallet, upStatus } from '../test/fixtures'
import { jsonResponse, noSession, stubApi } from '../test/http'
import { renderApp } from '../test/renderApp'

type Handlers = Parameters<typeof stubApi>[0][string]

/** No roulette route: the lobby must not request it. */
function backend(currentUser: Handlers = noSession()) {
  return stubApi({
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/users/me': currentUser,
    'GET /api/v1/wallet': () => jsonResponse(startingWallet),
  })
}

const lobbyLinks = () => [
  screen.queryByRole('link', { name: 'Open roulette' }),
  screen.queryByRole('link', { name: 'Open wallet' }),
]

describe('Home page', () => {
  it('keeps the welcome page for visitors: introduction, account panel and platform status', async () => {
    backend()
    renderApp('/')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Learn the odds. Play without risk.')
    const account = screen.getByRole('region', { name: 'Your account' })
    expect(await within(account).findByText(/You are signed out\./)).toBeInTheDocument()
    expect(within(account).getByRole('link', { name: 'Create account' })).toHaveAttribute('href', '/register')
    expect(screen.getByRole('region', { name: 'Platform status' })).toBeInTheDocument()
    expect(lobbyLinks()).toEqual([null, null])
  })

  it('shows a signed-in player the lobby with the server balance, honest progress, the account panel and the status', async () => {
    const api = backend(() => jsonResponse(alice))
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })).toBeInTheDocument()
    expect(screen.getByText(/Your casino lobby\./)).toHaveTextContent('virtual coins with no monetary value')

    const roulette = screen.getByRole('region', { name: 'European roulette' })
    // No availability claim (§7.7): the rules and who draws the number, not "play now".
    expect(within(roulette).getByText('Virtual coins')).toBeInTheDocument()
    expect(within(roulette).getByText(/Every number is drawn by the server; your browser never decides a result/)).toBeInTheDocument()
    expect(within(roulette).queryByText(/Not available/)).not.toBeInTheDocument()
    expect(within(roulette).getByText(/house edge of 1\/37/)).toBeInTheDocument()
    expect(within(roulette).getByRole('link', { name: 'Open roulette' })).toHaveAttribute('href', '/roulette')

    const wallet = screen.getByRole('region', { name: 'Wallet' })
    expect(within(wallet).getByText('History available')).toBeInTheDocument()
    expect(within(wallet).getByText(/See your transaction history, newest first/)).toBeInTheDocument()
    expect(within(wallet).getByRole('link', { name: 'Open wallet' })).toHaveAttribute('href', '/wallet')

    expect(within(screen.getByRole('region', { name: 'Your account' })).getByText('alice@example.com')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Platform status' })).toBeInTheDocument()
    // The balance is the server's, in the lobby card and the header; no other coin amount is shown.
    expect(await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,000 coins')).toBeInTheDocument()
    expect(screen.getAllByText(/\d[\d,]* coins/).map((element) => element.textContent)).toEqual(['1,000 coins', '1,000 coins'])
    expect(api.requests.map((request) => `${request.method} ${request.path}`).sort()).toEqual([
      'GET /api/v1/system/status',
      'GET /api/v1/users/me',
      'GET /api/v1/wallet',
    ])
  })

  it('asks for the platform status once, also when the welcome page turns into the lobby', async () => {
    const api = backend(() => jsonResponse(alice))
    renderApp('/')

    await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })
    expect(await within(screen.getByRole('region', { name: 'Platform status' })).findAllByText('UP')).not.toHaveLength(0)

    expect(api.requestsTo('GET /api/v1/system/status')).toHaveLength(1)
  })

  it('leads from the lobby to the roulette page', async () => {
    backend(() => jsonResponse(alice))
    renderApp('/')

    await userEvent.setup().click(await screen.findByRole('link', { name: 'Open roulette' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'European roulette' })).toBeInTheDocument()
  })

  it('shows an administrator the welcome page with a note and no player actions', async () => {
    backend(() => jsonResponse(admin))
    renderApp('/')

    expect(await screen.findByText(/signed in as an administrator/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Learn the odds.')
    expect(within(screen.getByRole('region', { name: 'Your account' })).getByText('admin@example.com')).toBeInTheDocument()
    expect(lobbyLinks()).toEqual([null, null])
  })
})
