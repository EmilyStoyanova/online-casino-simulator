import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode, useEffect } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppLayout } from '../../components/layout/AppLayout'
import { apiGet } from '../../services/apiClient'
import { admin, alice, interestCatalog, PASSWORD, startingWallet, upStatus } from '../../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, noSession, problemResponse, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'
import type { UserProfile } from '../../types/user'
import type { Wallet } from '../../types/wallet'
import { App } from '../../App'
import { AuthProvider } from '../auth/AuthProvider'
import { PlayerOnly } from '../auth/PlayerOnly'
import { RequireAuth } from '../auth/RequireAuth'
import { HomePage } from '../../pages/HomePage'
import { LoginPage } from '../../pages/LoginPage'
import { RoulettePage } from '../../pages/RoulettePage'
import type { WalletContextValue } from './walletContext'
import { useWallet } from './useWallet'
import { WalletProvider } from './WalletProvider'

const WALLET = 'GET /api/v1/wallet'
const CURRENT_USER = 'GET /api/v1/users/me'
/** An implemented protected request the tested pages do not send, answered as if the session had ended. */
const EXPIRING = '/api/v1/wallet/transactions'

const bob: UserProfile = {
  ...alice,
  id: '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f',
  email: 'bob@example.com',
  displayName: 'Bob',
}

/** A route of the fake backend: one handler, or several answering consecutive requests. */
type Route = Parameters<typeof stubApi>[0][string]
type Handler = Exclude<Route, readonly unknown[]>

const wallet = (balance: number, updatedAt = '2026-11-24T10:00:00Z'): Wallet => ({ balance, updatedAt })

/** A handler whose answers the test sends later, one per request, in request order. */
function controlled() {
  const resolvers: ((response: Response) => void)[] = []
  return {
    handler: (() => new Promise<Response>((resolve) => resolvers.push(resolve))) as Handler,
    count: () => resolvers.length,
    respond: (index: number, response: Response) =>
      act(async () => {
        const resolve = resolvers[index]
        if (!resolve) throw new Error(`No request ${index} yet`)
        resolve(response)
      }),
  }
}

function backend({
  currentUser = () => jsonResponse(alice),
  wallet: walletHandler = () => jsonResponse(startingWallet),
}: { currentUser?: Route; wallet?: Route } = {}) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/interests': () => jsonResponse(interestCatalog),
    [CURRENT_USER]: currentUser,
    [WALLET]: walletHandler,
    // Signs in whoever the form names (Alice or Bob); Spring deletes the CSRF cookie at sign-in.
    'POST /api/v1/auth/login': (init) => {
      deleteCookie('XSRF-TOKEN')
      const { email } = JSON.parse(String(init.body)) as { email: string }
      return jsonResponse(email === bob.email ? bob : email === admin.email ? admin : alice)
    },
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      return noContent()
    },
    [`GET ${EXPIRING}`]: () => problemResponse(401, 'SESSION_EXPIRED'),
  })
}

const accountNav = () => screen.getByRole('navigation', { name: 'Account' })
const mainNav = () => screen.getByRole('navigation', { name: 'Main' })
const balanceCard = () => screen.getByRole('region', { name: 'Your balance' })
/** Every coin amount on the page. */
const amounts = () => screen.queryAllByText(/\d[\d,]* coins?$/).map((element) => element.textContent)

async function signIn(email: string) {
  const user = userEvent.setup()
  await user.click(await within(accountNav()).findByRole('link', { name: 'Sign in' }))
  await user.type(await screen.findByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

async function signOut() {
  await userEvent.setup().click(within(accountNav()).getByRole('button', { name: 'Sign out' }))
  await screen.findByText(/You are signed out\./)
}

/**
 * The app's providers and layout with the lobby and the roulette page, plus a probe that hands the
 * wallet context to the test (for `applyConfirmedBalance`, which no Week 5 page calls before W5-07).
 */
function renderWithProbe(path = '/') {
  const probe: { current: WalletContextValue | null } = { current: null }
  function Probe() {
    const value = useWallet()
    useEffect(() => {
      probe.current = value
    })
    return null
  }
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <WalletProvider>
          <AppLayout>
            <Probe />
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/login" element={<LoginPage />} />
              <Route element={<RequireAuth />}>
                <Route element={<PlayerOnly />}>
                  <Route path="/roulette" element={<RoulettePage />} />
                </Route>
              </Route>
            </Routes>
          </AppLayout>
        </WalletProvider>
      </AuthProvider>
    </MemoryRouter>,
  )
  const wallet = () => {
    if (!probe.current) throw new Error('Wallet context not rendered yet')
    return probe.current
  }
  return { wallet }
}

describe('AC-W5-04.1: one wallet read per player session, none for others', () => {
  it('reads the balance once after a reload, and navigation does not read it again', async () => {
    const api = backend()
    renderApp('/')
    const user = userEvent.setup()

    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toHaveAttribute('href', '/wallet')
    await user.click(within(mainNav()).getByRole('link', { name: 'Roulette' }))
    await screen.findByRole('heading', { level: 1, name: 'European roulette' })
    await user.click(within(mainNav()).getByRole('link', { name: 'Wallet' }))
    await screen.findByRole('heading', { level: 1, name: 'Wallet' })
    await user.click(within(accountNav()).getByRole('link', { name: 'My profile' }))
    await screen.findByRole('heading', { level: 1, name: 'My profile' })
    await user.click(within(mainNav()).getByRole('link', { name: 'Lobby' }))
    await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })

    expect(api.requestsTo(WALLET)).toHaveLength(1)
    // Like every read: cookies only, no CSRF token, no body.
    expect(api.requestsTo(WALLET)[0]?.headers).toEqual({ Accept: 'application/json, application/problem+json' })
  })

  it('reads the balance once after a player signs in', async () => {
    const api = backend({ currentUser: noSession() })
    renderApp('/')
    await within(accountNav()).findByRole('link', { name: 'Sign in' })
    expect(api.requestsTo(WALLET)).toHaveLength(0)

    await signIn(alice.email)

    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(1)
  })

  it('sends no wallet request for visitors and shows no balance', async () => {
    const api = backend({ currentUser: noSession() })
    renderApp('/')

    expect(await screen.findByText(/You are signed out\./)).toBeInTheDocument()
    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(0)
  })

  it('sends no wallet request for administrators, also on the player pages', async () => {
    const api = backend({ currentUser: () => jsonResponse(admin) })
    renderApp('/wallet')

    expect(await screen.findByRole('heading', { name: 'For player accounts only' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('link', { name: 'Back to the home page' }))
    expect(await screen.findByText(/signed in as an administrator/)).toBeInTheDocument()
    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(0)
  })

  it('sends no wallet request while the session is being checked or cannot be checked', async () => {
    const check = controlled()
    const api = backend({ currentUser: check.handler })
    renderApp('/')

    expect(within(accountNav()).getByText('Checking sign-in…')).toBeInTheDocument()
    await check.respond(0, problemResponse(500, 'INTERNAL_ERROR'))

    expect(await screen.findByText(/could not be checked/)).toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(0)
  })

  it('in StrictMode applies one read: the trial read of the first effect is aborted', async () => {
    const api = backend()
    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )

    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
    const signals = api.fetchMock.mock.calls
      .filter(([input]) => String(input) === '/api/v1/wallet')
      .map(([, init]) => init?.signal)
    expect(signals.filter((signal) => signal?.aborted === false)).toHaveLength(1)
    expect(signals.length).toBeLessThanOrEqual(2)
  })
})

describe('AC-W5-04.2: one balance in the header, the lobby and the roulette page', () => {
  it('shows the same server balance with "coins" everywhere, never a currency symbol', async () => {
    const api = backend({ wallet: () => jsonResponse(wallet(1330)) })
    renderApp('/')

    expect(await screen.findByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(within(balanceCard()).getByText('1,330 coins')).toBeInTheDocument()
    expect(within(balanceCard()).getByText(/^Updated /)).toBeInTheDocument()
    expect(within(balanceCard()).getByText('Virtual coins have no monetary value.')).toBeInTheDocument()

    await userEvent.setup().click(within(mainNav()).getByRole('link', { name: 'Roulette' }))
    await screen.findByRole('heading', { level: 1, name: 'European roulette' })

    expect(amounts()).toEqual(['1,330 coins', '1,330 coins'])
    expect(document.body.textContent).not.toMatch(/[€$£¥]/)
    expect(api.requestsTo(WALLET)).toHaveLength(1)
  })

  it('updates the header, the lobby and the roulette page together (one shared state)', async () => {
    backend({ wallet: [() => jsonResponse(startingWallet), () => jsonResponse(wallet(1330))] })
    const { wallet: context } = renderWithProbe('/roulette')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    act(() => context().applyConfirmedBalance(1330))

    expect(screen.getByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(amounts()).toEqual(['1,330 coins', '1,330 coins'])
  })
})

describe('AC-W5-04.3: no balance after a session ends or for another account', () => {
  it('clears the balance as soon as the player signs out', async () => {
    backend()
    renderApp('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    await signOut()

    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
    expect(amounts()).toEqual([])
  })

  it('clears the balance when the session expires', async () => {
    backend()
    renderApp('/roulette')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    await act(async () => {
      await apiGet(EXPIRING).catch(() => undefined)
    })

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
    expect(amounts()).toEqual([])
  })

  it('never shows the previous player’s balance to the next account', async () => {
    const bobWallet = controlled()
    let reads = 0
    backend({
      wallet: (init) => {
        reads += 1
        return reads === 1 ? jsonResponse(wallet(1000)) : bobWallet.handler(init)
      },
    })
    renderApp('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })
    await signOut()

    await signIn(bob.email)

    // Bob's read is still on its way: a placeholder, not Alice's 1,000.
    expect(await screen.findByText('Loading your balance…')).toBeInTheDocument()
    expect(amounts()).toEqual([])
    await waitFor(() => expect(bobWallet.count()).toBe(1))
    await bobWallet.respond(0, jsonResponse(wallet(250)))
    expect(await screen.findByRole('link', { name: 'Balance 250 coins' })).toBeInTheDocument()
  })

  it('resets during render correctly in StrictMode when the account changes', async () => {
    const bobWallet = controlled()
    backend({
      wallet: (init) => {
        const aliceSignedIn = within(accountNav()).queryByText('Alice') !== null
        return aliceSignedIn ? jsonResponse(wallet(1000)) : bobWallet.handler(init)
      },
    })
    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/']}>
          <App />
        </MemoryRouter>
      </StrictMode>,
    )
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })
    await signOut()

    await signIn(bob.email)

    expect(await screen.findByText('Loading your balance…')).toBeInTheDocument()
    expect(amounts()).toEqual([])
    await waitFor(() => expect(bobWallet.count()).toBeGreaterThan(0))
    // Answer every read StrictMode started for Bob; only the latest may be applied.
    for (let index = 0; index < bobWallet.count(); index += 1) await bobWallet.respond(index, jsonResponse(wallet(250)))
    expect(await screen.findByRole('link', { name: 'Balance 250 coins' })).toBeInTheDocument()
    expect(amounts()).toEqual(['250 coins', '250 coins'])
  })

  it('ignores a late answer for an earlier account', async () => {
    const reads = controlled()
    backend({ wallet: reads.handler })
    renderApp('/')
    await waitFor(() => expect(reads.count()).toBe(1)) // Alice's read, unanswered
    await signOut()
    await signIn(bob.email)
    await waitFor(() => expect(reads.count()).toBe(2)) // Bob's read

    await reads.respond(1, jsonResponse(wallet(250)))
    await screen.findByRole('link', { name: 'Balance 250 coins' })
    await reads.respond(0, jsonResponse(wallet(1000))) // Alice's answer arrives last

    expect(screen.getByRole('link', { name: 'Balance 250 coins' })).toBeInTheDocument()
    expect(amounts()).toEqual(['250 coins', '250 coins'])
  })

  it('ignores a late answer of an earlier session of the same player', async () => {
    const reads = controlled()
    backend({ wallet: reads.handler })
    renderApp('/')
    await waitFor(() => expect(reads.count()).toBe(1))
    await signOut()
    await signIn(alice.email)
    await waitFor(() => expect(reads.count()).toBe(2))

    await reads.respond(1, jsonResponse(wallet(900)))
    await screen.findByRole('link', { name: 'Balance 900 coins' })
    await reads.respond(0, jsonResponse(wallet(1000)))

    expect(screen.getByRole('link', { name: 'Balance 900 coins' })).toBeInTheDocument()
  })

  it('ignores a late answer that arrives after the sign-out', async () => {
    const reads = controlled()
    backend({ wallet: reads.handler })
    renderApp('/')
    await waitFor(() => expect(reads.count()).toBe(1))
    await signOut()

    await reads.respond(0, jsonResponse(wallet(1000)))

    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
  })
})

describe('AC-W5-04.4: a confirmed balance is never overwritten by an older read', () => {
  it('drops a read that started before applyConfirmedBalance and reconciles with a new one', async () => {
    const reads = controlled()
    const api = backend({ wallet: reads.handler })
    const { wallet: context } = renderWithProbe('/')
    await waitFor(() => expect(reads.count()).toBe(1))
    await reads.respond(0, jsonResponse(wallet(1000)))
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    act(() => context().refresh()) // read 2: may carry the balance before the spin
    await waitFor(() => expect(reads.count()).toBe(2))
    act(() => context().applyConfirmedBalance(1330)) // the spin's confirmed balance; read 3 reconciles
    await waitFor(() => expect(reads.count()).toBe(3))

    // Shown at once, without an invented timestamp.
    expect(screen.getByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(within(balanceCard()).getByText('Confirmed by the server with your last round.')).toBeInTheDocument()
    expect(within(balanceCard()).queryByText(/^Updated /)).not.toBeInTheDocument()
    expect(balanceCard()).toHaveAttribute('aria-busy', 'true')

    await reads.respond(1, jsonResponse(wallet(1000))) // the older read answers late
    expect(screen.getByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()

    await reads.respond(2, jsonResponse(wallet(1330, '2026-11-24T10:05:00Z')))
    expect(within(balanceCard()).getByText(/^Updated /)).toBeInTheDocument()
    expect(balanceCard()).toHaveAttribute('aria-busy', 'false')
    // The older read was also aborted (read 1 had finished already); the reconciling read was not.
    const signals = api.fetchMock.mock.calls.filter(([input]) => String(input) === '/api/v1/wallet').map(([, init]) => init?.signal)
    expect(signals).toHaveLength(3)
    expect(signals[1]?.aborted).toBe(true)
    expect(signals[2]?.aborted).toBe(false)
  })

  it('takes the server’s value from the reconciling read (e.g. a change in another tab)', async () => {
    backend({ wallet: [() => jsonResponse(wallet(1000)), () => jsonResponse(wallet(1325))] })
    const { wallet: context } = renderWithProbe('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    act(() => context().applyConfirmedBalance(1330))

    expect(await screen.findByRole('link', { name: 'Balance 1,325 coins' })).toBeInTheDocument()
  })

  it('keeps the confirmed value, marked as possibly out of date, when the reconciling read fails', async () => {
    backend({ wallet: [() => jsonResponse(wallet(1000)), () => problemResponse(500, 'INTERNAL_ERROR')] })
    const { wallet: context } = renderWithProbe('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    act(() => context().applyConfirmedBalance(1330))

    expect(await within(balanceCard()).findByRole('alert')).toHaveTextContent('may be out of date')
    expect(screen.getByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(screen.getByText('Not up to date')).toBeInTheDocument()
  })

  it('ignores a confirmed balance of an earlier session (taken before a change of account)', async () => {
    const api = backend({ wallet: [() => jsonResponse(wallet(1000)), () => jsonResponse(wallet(250))] })
    const { wallet: context } = renderWithProbe('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })
    const aliceSession = context().applyConfirmedBalance
    await signOut()
    await signIn(bob.email)
    await screen.findByRole('link', { name: 'Balance 250 coins' })

    act(() => aliceSession(5000))

    expect(screen.getByRole('link', { name: 'Balance 250 coins' })).toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(2)
  })

  it('does nothing without a player session, and rejects values that are not whole coins', async () => {
    const api = backend({ currentUser: noSession() })
    const { wallet: context } = renderWithProbe('/')
    await screen.findByText(/You are signed out\./)

    act(() => context().applyConfirmedBalance(100))
    act(() => context().refresh())

    expect(screen.queryByText(/Balance/)).not.toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(0)
    for (const invalid of [-1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() => context().applyConfirmedBalance(invalid)).toThrow(RangeError)
    }
  })
})

describe('AC-W5-04.5: placeholders and failures, never 0 or a stale value presented as current', () => {
  it('shows a labelled placeholder while the first read is in flight, never a number', async () => {
    const reads = controlled()
    backend({ wallet: reads.handler })
    renderApp('/')

    const badge = await screen.findByText('Loading…')
    expect(badge.parentElement).toHaveTextContent('Balance Loading…')
    expect(badge.parentElement).toHaveAttribute('aria-busy', 'true')
    expect(within(balanceCard()).getByText('Loading your balance…')).toBeInTheDocument()
    expect(balanceCard()).toHaveAttribute('aria-busy', 'true')
    expect(amounts()).toEqual([])
    expect(screen.queryByText(/\b0 coins/)).not.toBeInTheDocument()

    await waitFor(() => expect(reads.count()).toBe(1))
    await reads.respond(0, jsonResponse(wallet(0)))
    // A real zero from the server is shown as such.
    expect(await screen.findByRole('link', { name: 'Balance 0 coins' })).toBeInTheDocument()
  })

  it('shows "Balance unavailable" after a network error, and the card’s retry loads it', async () => {
    const api = backend({ wallet: [() => Promise.reject(new TypeError('Failed to fetch')), () => jsonResponse(startingWallet)] })
    renderApp('/')

    expect(await within(await screen.findByRole('region', { name: 'Your balance' })).findByText('Balance unavailable')).toBeInTheDocument()
    expect(within(balanceCard()).getByText('The server could not be reached. Please check your connection.')).toBeInTheDocument()
    expect(within(accountNav().parentElement as HTMLElement).getByText('Balance unavailable')).toBeInTheDocument()
    expect(amounts()).toEqual([])

    await userEvent.setup().click(within(balanceCard()).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
    expect(api.requestsTo(WALLET)).toHaveLength(2)
  })

  it('shows "Balance unavailable" after a server error, and the header’s retry loads it', async () => {
    backend({ wallet: [() => problemResponse(500, 'INTERNAL_ERROR'), () => jsonResponse(startingWallet)] })
    renderApp('/')

    expect(
      await within(await screen.findByRole('region', { name: 'Your balance' })).findByText('Your balance could not be loaded.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Balance unavailable', { selector: 'header span' })).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Retry loading the balance' }))

    expect(await screen.findByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
  })

  it('keeps the value visible during a refresh and marks it when the refresh fails, until a retry succeeds', async () => {
    const reads = controlled()
    backend({ wallet: reads.handler })
    renderApp('/')
    await waitFor(() => expect(reads.count()).toBe(1))
    await reads.respond(0, jsonResponse(wallet(1000)))
    const user = userEvent.setup()

    await user.click(await within(balanceCard()).findByRole('button', { name: 'Refresh' }))

    // No flicker: the last confirmed value stays, the card says it is refreshing.
    expect(within(balanceCard()).getByRole('button', { name: 'Refreshing…' })).toBeDisabled()
    expect(balanceCard()).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('link', { name: 'Balance 1,000 coins' })).toHaveAttribute('aria-busy', 'true')

    await waitFor(() => expect(reads.count()).toBe(2))
    await reads.respond(1, problemResponse(503, 'SERVICE_UNAVAILABLE'))

    expect(within(balanceCard()).getByRole('alert')).toHaveTextContent('could not be refreshed, so it may be out of date')
    expect(screen.getByText('Not up to date')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Retry loading the balance' }))
    await waitFor(() => expect(reads.count()).toBe(3))
    await reads.respond(2, jsonResponse(wallet(1100)))

    expect(await screen.findByRole('link', { name: 'Balance 1,100 coins' })).toBeInTheDocument()
    expect(screen.queryByText('Not up to date')).not.toBeInTheDocument()
    expect(within(balanceCard()).queryByRole('alert')).not.toBeInTheDocument()
  })
})
