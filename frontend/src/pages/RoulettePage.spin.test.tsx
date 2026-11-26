import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { pendingSpinStorageKey, type PendingSpin } from '../features/roulette/pendingSpinStore'
import { REVEAL_LIMIT_MS } from '../features/roulette/useSpin'
import { fakeWheel } from '../test/fakeWheelGame'
import { alice, PASSWORD, SPIN_KEY, spinRequest, spinResult, startingWallet, upStatus } from '../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, problemResponse, stubApi } from '../test/http'
import type { RouletteSpinResult } from '../types/roulette'
import type { UserProfile } from '../types/user'

const SPIN = 'POST /api/v1/games/roulette/spins'
const WALLET = 'GET /api/v1/wallet'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

const bob: UserProfile = { ...alice, id: '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f', email: 'bob@example.com', displayName: 'Bob' }

type Route = Parameters<typeof stubApi>[0][string]
type Handler = Exclude<Route, readonly unknown[]>

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

const networkError: Handler = () => Promise.reject(new TypeError('Failed to fetch'))

function backend({
  spin = () => jsonResponse(spinResult),
  wallet = [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 1330, updatedAt: '2026-11-20T10:15:31Z' })],
  user = alice,
  schemaVersion = '9',
}: { spin?: Route; wallet?: Route; user?: UserProfile | null; schemaVersion?: string } = {}) {
  let signedIn: UserProfile | null = user
  return stubApi({
    'GET /api/v1/system/status': () => jsonResponse({ ...upStatus, database: { status: 'UP', schemaVersion } }),
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/users/me': () => (signedIn ? jsonResponse(signedIn) : problemResponse(401, 'AUTHENTICATION_REQUIRED')),
    [WALLET]: wallet,
    [SPIN]: spin,
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      signedIn = null
      return noContent()
    },
    'POST /api/v1/auth/login': (init) => {
      deleteCookie('XSRF-TOKEN')
      const { email } = JSON.parse(String(init.body)) as { email: string }
      signedIn = email === bob.email ? bob : alice
      return jsonResponse(signedIn)
    },
  })
}

/** The whole app, with the current location for the redirect assertions. */
function renderAt(path: string) {
  const location = { current: '' }
  function Probe() {
    const here = useLocation()
    location.current = `${here.pathname}${here.search}`
    return null
  }
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <Probe />
    </MemoryRouter>,
  )
  return location
}

const table = () => screen.getByRole('region', { name: 'Place your bets' })
const slip = () => screen.getByRole('region', { name: 'Bet slip' })
const cell = (name: RegExp) => within(table()).getByRole('button', { name })
const slipButton = (name: string | RegExp) => within(slip()).getByRole('button', { name })
const headerBalance = () => screen.getByRole('link', { name: /^Balance / })
const stored = (userId = alice.id) => {
  const raw = sessionStorage.getItem(pendingSpinStorageKey(userId))
  return raw === null ? null : (JSON.parse(raw) as PendingSpin)
}
const posts = (api: ReturnType<typeof stubApi>) => api.requestsTo(SPIN)

async function openRoulette() {
  const location = renderAt('/roulette')
  await screen.findByRole('region', { name: 'Place your bets' })
  await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,000 coins')
  return location
}

/** The bets of the contract example: 10 on 17 and 20 on red (chip 10). */
async function placeExampleBets(user: ReturnType<typeof userEvent.setup>) {
  await user.click(cell(/^17, black/))
  await user.click(cell(/^Red\./))
  await user.click(cell(/^Red\./))
}

async function reviewAndConfirm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(slipButton('Review bets'))
  await user.click(slipButton('Confirm and spin'))
}

const unresolvedRecord = (overrides: Partial<PendingSpin> = {}): PendingSpin => ({
  version: 1,
  userId: alice.id,
  idempotencyKey: SPIN_KEY,
  request: spinRequest,
  createdAt: '2026-11-25T09:00:00.000Z',
  ambiguous: true,
  state: 'unresolved',
  ...overrides,
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('AC-W5-07.1: review, then an explicit confirmation', () => {
  it('sends nothing on page load, on placing bets or on "Review bets"; "Back to bets" changes nothing', async () => {
    const api = backend()
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)

    await user.click(slipButton('Review bets'))

    expect(within(slip()).getByRole('heading', { name: 'Review your bets' })).toHaveFocus()
    expect(within(slip()).getByText('The server draws the number. Your stake is debited when you confirm.')).toBeInTheDocument()
    expect(within(slip()).getByText('30 coins')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(0)

    await user.click(slipButton('Back to bets'))

    expect(slipButton('Review bets')).toHaveFocus()
    expect(within(slip()).getAllByRole('listitem')).toHaveLength(2)
    expect(posts(api)).toHaveLength(0)
    expect(api.requests.every((request) => request.method === 'GET')).toBe(true)
  })
})

describe('AC-W5-07.2 / 07.3: the confirmed round', () => {
  it('sends one request with a new key and the frozen body, written to sessionStorage before it left', async () => {
    let recordAtSend: PendingSpin | null = null
    const api = backend({
      spin: () => {
        recordAtSend = stored()
        return jsonResponse(spinResult)
      },
    })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)

    await reviewAndConfirm(user)

    await screen.findByRole('heading', { name: 'Result' })
    const [request] = posts(api)
    expect(posts(api)).toHaveLength(1)
    expect(request?.body).toEqual(spinRequest)
    expect(request?.headers['Idempotency-Key']).toMatch(UUID)
    expect(request?.headers['X-XSRF-TOKEN']).toBe('csrf-1')
    // AC-W5-07.4a: the record was there, with this key and body, before the request reached the server.
    expect(recordAtSend).toEqual({
      version: 1,
      userId: alice.id,
      idempotencyKey: request?.headers['Idempotency-Key'],
      request: spinRequest,
      createdAt: expect.any(String),
      ambiguous: false,
      state: 'sending',
    })
  })

  it('turns the wheel to the server’s number, then shows the server’s result; slip and record cleared', async () => {
    backend()
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)

    await reviewAndConfirm(user)

    const result = await screen.findByRole('region', { name: 'Result' })
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledTimes(1)
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledWith(17)
    expect(within(result).getByRole('heading', { name: 'Result' })).toHaveFocus()
    expect(within(result).getByText('17 · Black')).toBeInTheDocument()
    expect(within(result).getByRole('status')).toHaveTextContent('Staked 30 coins, returned 360 coins, net +330 coins.')
    const rows = within(within(result).getByRole('list', { name: 'Your bets in this round' })).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      'Number 17Stake 10 coinsWonPayout 360 coins',
      'RedStake 20 coinsLostPayout 0 coins',
    ])
    expect(within(result).getByText('Net result').nextElementSibling).toHaveTextContent('+330 coins')
    expect(within(result).getByText('Balance after this round').nextElementSibling).toHaveTextContent('1,330 coins')
    expect(within(result).getByText(/^Played/)).toHaveTextContent(/^Played 20 Nov 2026, \d\d:15 · round 6f1c2a0e\. Drawn and settled by the server\.$/)
    // Q7: the slip is empty, no repeat-bet control; the record is gone.
    expect(within(slip()).getByText(/No bets yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /repeat|again|rebet/i })).not.toBeInTheDocument()
    expect(stored()).toBeNull()
  })

  it('shows the server balance at once, then the reconciled wallet read; never a guess before the answer', async () => {
    const spin = controlled()
    const api = backend({
      spin: spin.handler,
      wallet: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 1335, updatedAt: '2026-11-20T10:16:00Z' })],
    })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    await waitFor(() => expect(spin.count()).toBe(1))

    // Waiting: nothing is deducted or added by the client.
    expect(slipButton('Waiting for the server…')).toHaveAttribute('aria-disabled', 'true')
    expect(headerBalance()).toHaveAccessibleName('Balance 1,000 coins')

    await spin.respond(0, jsonResponse(spinResult))

    // The spin's balance first (1,330), then the reconciling read (another tab played meanwhile: 1,335).
    await waitFor(() => expect(api.requestsTo(WALLET)).toHaveLength(2))
    expect(await screen.findByRole('link', { name: 'Balance 1,335 coins' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Result' })).getByText('Balance after this round').nextElementSibling).toHaveTextContent(
      '1,330 coins',
    )
  })

  it('renders exactly what the server sent, even where the client could not have known it (no payout logic)', async () => {
    const zero: RouletteSpinResult = {
      ...spinResult,
      roundId: 'aaaaaaaa-8b3d-4c55-9a7e-2f0d5b1e9c41',
      winningNumber: 0,
      winningColor: 'GREEN',
      // Deliberately unusual values: the page shows them as sent and derives nothing but the net.
      bets: [
        { category: 'STRAIGHT', number: 17, amount: 10, payout: 0 },
        { category: 'RED_BLACK', color: 'RED', amount: 20, payout: 0 },
      ],
      totalPayout: 0,
      balance: 970,
    }
    backend({ spin: () => jsonResponse(zero), wallet: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 970, updatedAt: null })] })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    const result = await screen.findByRole('region', { name: 'Result' })
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledWith(0)
    expect(within(result).getByText('0 · Green')).toBeInTheDocument()
    expect(within(result).getAllByText('Lost')).toHaveLength(2)
    expect(within(result).getByText('Net result').nextElementSibling).toHaveTextContent('−30 coins')
  })

  it('AC-W5-07.5: the next spin gets a new key', async () => {
    const api = backend({ wallet: () => jsonResponse(startingWallet) })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    await screen.findByRole('region', { name: 'Result' })

    await placeExampleBets(user)
    await reviewAndConfirm(user)

    await waitFor(() => expect(posts(api)).toHaveLength(2))
    const [first, second] = posts(api).map((request) => request.headers['Idempotency-Key'])
    expect(first).toMatch(UUID)
    expect(second).toMatch(UUID)
    expect(second).not.toBe(first)
  })
})

describe('Phaser: the wheel only shows the server’s number', () => {
  it('a failed animation does not hide the result and sends nothing again', async () => {
    const api = backend()
    await openRoulette()
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    fakeWheel.live()[0]?.spinTo.mockRejectedValue(new Error('WebGL context lost'))
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)
    expect(headerBalance()).toHaveAccessibleName(/Balance 1,330 coins/)
  })

  it('without canvas support the HTML result is shown at once', async () => {
    fakeWheel.canvasSupported = false
    backend()
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(fakeWheel.instances).toHaveLength(0)
  })

  it('a wheel that never finishes delays the result at most REVEAL_LIMIT_MS', async () => {
    backend()
    await openRoulette()
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    fakeWheel.live()[0]?.spinTo.mockReturnValue(new Promise(() => undefined))
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByText(/The wheel is turning to the number it drew/)).toBeInTheDocument()
    expect(screen.queryByText('17 · Black')).not.toBeInTheDocument()
    await act(async () => {
      vi.advanceTimersByTime(REVEAL_LIMIT_MS)
    })
    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
  })

  it('with reduced motion the wheel is told so and still lands on the server’s number', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      media: query,
      matches: query.includes('reduce'),
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }))
    backend()
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(fakeWheel.live()[0]?.options.reducedMotion).toBe(true)
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledWith(17)
  })
})

describe('AC-W5-07.4 / 07.4a: unknown outcomes and the explicit retry', () => {
  it.each([
    ['a network error', networkError, /We could not confirm this spin/],
    ['500 INTERNAL_ERROR', () => problemResponse(500, 'INTERNAL_ERROR'), /We could not confirm this spin/],
    ['a gateway 502 without Problem Details', () => new Response('<html>Bad gateway</html>', { status: 502 }), /We could not confirm this spin/],
    ['503 SERVICE_UNAVAILABLE (busy wallet)', () => problemResponse(503, 'SERVICE_UNAVAILABLE'), /The wallet is busy/],
  ])('%s: the record is kept, betting is locked, "Retry spin" sends the same key and body', async (_, failure, message) => {
    const api = backend({ spin: [failure as Handler, () => jsonResponse(spinResult)] })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await within(slip()).findByRole('heading', { name: 'Spin not confirmed' })).toHaveFocus()
    expect(within(slip()).getByRole('alert')).toHaveTextContent(message)
    const key = posts(api)[0]?.headers['Idempotency-Key']
    expect(stored()).toMatchObject({ idempotencyKey: key, request: spinRequest, ambiguous: true, state: 'unresolved' })
    expect(cell(/^Black\./)).toHaveAttribute('aria-disabled', 'true')
    expect(cell(/^Black\./)).toHaveAccessibleDescription(/An earlier spin is not confirmed yet/)
    await user.click(cell(/^Black\./))
    expect(within(slip()).queryByRole('button', { name: 'Review bets' })).not.toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)

    await user.click(slipButton('Retry spin'))

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(2)
    expect(posts(api)[1]?.headers['Idempotency-Key']).toBe(key)
    expect(JSON.stringify(posts(api)[1]?.body)).toBe(JSON.stringify(posts(api)[0]?.body))
    expect(stored()).toBeNull()
  })

  const withoutBalance: Partial<RouletteSpinResult> = { ...spinResult }
  delete withoutBalance.balance
  it.each([
    ['an unrelated object', { ok: true, winningNumber: 99 }],
    ['null', null],
    ['no balance', withoutBalance],
    ['a winning number outside 0–36', { ...spinResult, winningNumber: 37 }],
    ['an unknown colour', { ...spinResult, winningColor: 'BLUE' }],
    ['no bets', { ...spinResult, bets: [] }],
    ['a negative payout', { ...spinResult, bets: [{ ...spinResult.bets[0], payout: -1 }, spinResult.bets[1]] }],
    ['a fractional balance', { ...spinResult, balance: 1330.5 }],
    ['a bet the table does not offer', { ...spinResult, bets: [{ category: 'COLUMN', column: 1, amount: 10, payout: 0 }] }],
  ])('a 200 with %s is an unknown outcome: nothing changed, the record kept; the retry sends the same key and body', async (_, body) => {
    const api = backend({ spin: [() => jsonResponse(body), () => jsonResponse(spinResult)] })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    // Validated before anything: no balance, no result, no animation, the record not cleared.
    expect(await within(slip()).findByRole('heading', { name: 'Spin not confirmed' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Result' })).not.toBeInTheDocument()
    expect(headerBalance()).toHaveAccessibleName('Balance 1,000 coins')
    expect(fakeWheel.live()[0]?.spinTo).not.toHaveBeenCalled()
    const key = posts(api)[0]?.headers['Idempotency-Key']
    expect(stored()).toMatchObject({ idempotencyKey: key, request: spinRequest, ambiguous: true, state: 'unresolved' })
    expect(api.requestsTo(WALLET)).toHaveLength(1)

    await user.click(slipButton('Retry spin'))

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)[1]?.headers['Idempotency-Key']).toBe(key)
    expect(JSON.stringify(posts(api)[1]?.body)).toBe(JSON.stringify(posts(api)[0]?.body))
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledTimes(1)
    expect(stored()).toBeNull()
  })

  it('the CSRF retry inside apiClient repeats the same key and body (no second spin)', async () => {
    const api = backend({ spin: [() => problemResponse(403, 'CSRF_TOKEN_INVALID'), () => jsonResponse(spinResult)] })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    const [first, second] = posts(api)
    expect(posts(api)).toHaveLength(2)
    expect(second?.headers['Idempotency-Key']).toBe(first?.headers['Idempotency-Key'])
    expect(second?.body).toEqual(first?.body)
    expect(second?.headers['X-XSRF-TOKEN']).not.toBe(first?.headers['X-XSRF-TOKEN'])
  })

  it('AC-W5-07.4a: if the record cannot be written, nothing is sent and the player is told', async () => {
    const api = backend()
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Quota exceeded', 'QuotaExceededError')
    })

    await reviewAndConfirm(user)

    expect(await within(slip()).findByRole('alert')).toHaveTextContent(/This spin was not sent: your browser could not save it safely/)
    expect(posts(api)).toHaveLength(0)
    expect(within(slip()).getAllByRole('listitem')).toHaveLength(2)
  })

  it('AC-W5-07.7: double activation of "Confirm and spin" sends one request', async () => {
    const spin = controlled()
    const api = backend({ spin: spin.handler })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await user.click(slipButton('Review bets'))
    const confirm = slipButton('Confirm and spin')

    act(() => {
      confirm.click()
      confirm.click()
    })
    await user.dblClick(within(slip()).getByRole('button', { name: /Confirm and spin|Waiting for the server/ }))

    await waitFor(() => expect(spin.count()).toBe(1))
    expect(posts(api)).toHaveLength(1)
    await spin.respond(0, jsonResponse(spinResult))
    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)
  })
})

describe('AC-W5-07.7: no second request', () => {
  it('double activation of "Retry spin" sends one request with the record’s key', async () => {
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), JSON.stringify(unresolvedRecord()))
    const spin = controlled()
    const api = backend({ spin: spin.handler })
    await openRoulette()
    const retry = slipButton('Retry spin')

    act(() => {
      retry.click()
      retry.click()
    })

    await waitFor(() => expect(spin.count()).toBe(1))
    expect(posts(api)).toHaveLength(1)
    expect(posts(api)[0]?.headers['Idempotency-Key']).toBe(SPIN_KEY)
    await spin.respond(0, jsonResponse(spinResult))
    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
  })

  it('a balance that falls below the stake during the confirmation blocks "Confirm and spin" with the reason', async () => {
    const api = backend({
      wallet: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 25, updatedAt: '2026-11-25T08:00:00Z' })],
    })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await user.click(slipButton('Review bets'))

    await user.click(within(screen.getByRole('region', { name: 'Your balance' })).getByRole('button', { name: 'Refresh' }))
    await within(screen.getByRole('region', { name: 'Your balance' })).findByText('25 coins')

    const confirm = slipButton('Confirm and spin')
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    expect(confirm).toHaveAccessibleDescription('Your balance is too low for these bets: 30 coins in bets, 25 coins available.')
    await user.click(confirm)
    expect(posts(api)).toHaveLength(0)
    expect(stored()).toBeNull()
  })
})

describe('AC-W5-07.4b: after a reload, and for other users', () => {
  it('shows the unresolved spin of this user with its bets and sends nothing until "Retry spin"', async () => {
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), JSON.stringify(unresolvedRecord({ state: 'sending', ambiguous: false })))
    const api = backend()
    await openRoulette()

    expect(within(slip()).getByRole('heading', { name: 'Spin not confirmed' })).toBeInTheDocument()
    expect(within(slip()).getByRole('alert')).toHaveTextContent(/An earlier spin could not be confirmed/)
    expect(within(slip()).getAllByRole('listitem').map((row) => row.textContent?.split('pays')[0])).toEqual(['Number 17Straight bet · ', 'RedRed or black bet · '])
    expect(cell(/^0, green/)).toHaveAttribute('aria-disabled', 'true')
    expect(posts(api)).toHaveLength(0)

    await userEvent.setup().click(slipButton('Retry spin'))

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)
    expect(posts(api)[0]?.headers['Idempotency-Key']).toBe(SPIN_KEY)
    expect(JSON.stringify(posts(api)[0]?.body)).toBe(JSON.stringify(spinRequest))
    expect(stored()).toBeNull()
  })

  it('another user in the same tab never sees, sends or clears it', async () => {
    const record = JSON.stringify(unresolvedRecord())
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), record)
    const api = backend({ user: bob, wallet: () => jsonResponse({ balance: 500, updatedAt: '2026-11-25T08:00:00Z' }) })
    renderAt('/roulette')
    await screen.findByRole('region', { name: 'Place your bets' })

    expect(within(slip()).queryByRole('heading', { name: 'Spin not confirmed' })).not.toBeInTheDocument()
    expect(within(slip()).getByText(/No bets yet/)).toBeInTheDocument()
    expect(cell(/^0, green/)).not.toHaveAttribute('aria-disabled')
    expect(posts(api)).toHaveLength(0)
    expect(sessionStorage.getItem(pendingSpinStorageKey(alice.id))).toBe(record)
  })

  it('an invalid record is set aside with an explanation and the wallet is read again; nothing is sent', async () => {
    const api = backend({ wallet: () => jsonResponse(startingWallet) })
    renderAt('/')
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), '{"version":1,"userId":"someone-else"}')

    await userEvent.setup().click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Roulette' }))

    expect(await within(slip()).findByRole('alert')).toHaveTextContent(/An earlier spin could not be checked safely/)
    await waitFor(() => expect(api.requestsTo(WALLET)).toHaveLength(2))
    expect(stored()).toBeNull()
    expect(posts(api)).toHaveLength(0)
  })

  it('on a fresh page load an invalid record is set aside too; the page’s own first wallet read is the current balance', async () => {
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), '[]')
    const api = backend({ wallet: () => jsonResponse(startingWallet) })
    await openRoulette()

    expect(within(slip()).getByRole('alert')).toHaveTextContent(/An earlier spin could not be checked safely/)
    expect(stored()).toBeNull()
    expect(api.requestsTo(WALLET)).toHaveLength(1)
    expect(posts(api)).toHaveLength(0)
  })
})

describe('AC-W5-07.6: definitive failures', () => {
  it('400 VALIDATION_FAILED: the message on the slip, the field message on its row, the slip editable, the record cleared', async () => {
    backend({
      spin: () =>
        problemResponse(400, 'VALIDATION_FAILED', { errors: [{ field: 'bets[1].amount', message: 'must be at most 1000' }] }),
    })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    const alert = await within(slip()).findByRole('alert')
    expect(alert).toHaveTextContent('Some bets were not accepted. Change them and review again.')
    expect(alert.parentElement).toHaveFocus()
    const [straight, red] = within(slip()).getAllByRole('listitem')
    expect(red).toHaveTextContent('must be at most 1000')
    expect(straight).not.toHaveTextContent('must be at most 1000')
    expect(stored()).toBeNull()
    expect(cell(/^Black\./)).not.toHaveAttribute('aria-disabled')

    await user.click(cell(/^Black\./))
    expect(within(slip()).queryByRole('alert')).not.toBeInTheDocument()
  })

  it.each([
    [409, 'INSUFFICIENT_FUNDS', 'Your balance is too low for these bets.', 2],
    [409, 'BALANCE_LIMIT_EXCEEDED', 'This spin cannot be played with your current balance.', 2],
    [409, 'IDEMPOTENCY_KEY_REUSED', 'Check your wallet history for the round', 2],
    [400, 'MALFORMED_REQUEST', 'The request could not be processed.', 1],
    [403, 'ACCESS_DENIED', 'Roulette is for player accounts.', 1],
  ])('%i %s: message, record cleared, bets kept, wallet reads: %i', async (status, code, message, walletReads) => {
    const defectLog = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const api = backend({ spin: () => problemResponse(status, code), wallet: () => jsonResponse(startingWallet) })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await within(slip()).findByRole('alert')).toHaveTextContent(message)
    expect(stored()).toBeNull()
    expect(within(slip()).getAllByRole('listitem')).toHaveLength(2)
    await waitFor(() => expect(api.requestsTo(WALLET)).toHaveLength(walletReads))
    expect(posts(api)).toHaveLength(1)
    expect(screen.queryByRole('region', { name: 'Result' })).not.toBeInTheDocument()
    // §8.4: a reused key cannot happen with a frozen body; it is logged as a defect (and only then).
    const logged = defectLog.mock.calls.filter(([text]) => String(text).includes('IDEMPOTENCY_KEY_REUSED'))
    expect(logged).toHaveLength(code === 'IDEMPOTENCY_KEY_REUSED' ? 1 : 0)
  })

  it('403 CSRF_TOKEN_INVALID after the automatic retry: definitive message, two requests with one key', async () => {
    const api = backend({ spin: () => problemResponse(403, 'CSRF_TOKEN_INVALID') })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await within(slip()).findByRole('alert')).toHaveTextContent('Your security token could not be renewed.')
    expect(new Set(posts(api).map((request) => request.headers['Idempotency-Key'])).size).toBe(1)
    expect(posts(api)).toHaveLength(2)
    expect(stored()).toBeNull()
  })
})

describe('AC-W5-07.4c / 07.8: authentication interruptions', () => {
  it('401 SESSION_EXPIRED on a first send: login with returnTo=/roulette; nothing was played, the record is cleared', async () => {
    backend({ spin: () => problemResponse(401, 'SESSION_EXPIRED') })
    const location = await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(location.current).toBe('/login?returnTo=%2Froulette')
    expect(stored()).toBeNull()
  })

  it('401 after an earlier unknown outcome keeps the record; the same player signs in again and is offered the retry', async () => {
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), JSON.stringify(unresolvedRecord()))
    const api = backend({ spin: [() => problemResponse(401, 'SESSION_EXPIRED'), () => jsonResponse(spinResult)] })
    const location = await openRoulette()
    const user = userEvent.setup()

    await user.click(slipButton('Retry spin'))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(location.current).toBe('/login?returnTo=%2Froulette')
    expect(stored()).toMatchObject({ idempotencyKey: SPIN_KEY, ambiguous: true, state: 'unresolved' })

    await user.type(screen.getByLabelText('Email'), alice.email)
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await within(await screen.findByRole('region', { name: 'Bet slip' })).findByRole('heading', { name: 'Spin not confirmed' })).toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)
    await user.click(slipButton('Retry spin'))
    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(posts(api)[1]?.headers['Idempotency-Key']).toBe(SPIN_KEY)
  })

  it('403 ACCESS_DENIED after an earlier unknown outcome keeps the record too', async () => {
    sessionStorage.setItem(pendingSpinStorageKey(alice.id), JSON.stringify(unresolvedRecord()))
    backend({ spin: () => problemResponse(403, 'ACCESS_DENIED') })
    await openRoulette()

    await userEvent.setup().click(slipButton('Retry spin'))

    expect(await within(slip()).findByRole('alert')).toHaveTextContent(/Roulette is for player accounts\. Your earlier spin is still not confirmed/)
    expect(stored()).toMatchObject({ idempotencyKey: SPIN_KEY, ambiguous: true, state: 'unresolved' })
  })
})

describe('AC-W5-07.10: a backend without roulette', () => {
  it('a 404 puts the page into a persistent unavailable state; the lobby says so; the wallet still works', async () => {
    const api = backend({ spin: () => problemResponse(404, 'NOT_FOUND'), wallet: () => jsonResponse(startingWallet) })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)

    expect(await within(table()).findByText('Roulette is not available on this server yet.')).toBeInTheDocument()
    expect(stored()).toBeNull()
    expect(cell(/^Black\./)).toHaveAttribute('aria-disabled', 'true')
    expect(slipButton('Review bets')).toHaveAccessibleDescription('Roulette is not available on this server yet.')
    await user.click(slipButton('Review bets'))
    expect(screen.queryByRole('button', { name: 'Confirm and spin' })).not.toBeInTheDocument()

    const nav = () => screen.getByRole('navigation', { name: 'Main' })
    await user.click(within(nav()).getByRole('link', { name: 'Lobby' }))
    const lobbyCard = await screen.findByRole('region', { name: 'European roulette' })
    expect(within(lobbyCard).getByText('Not available on this server yet')).toBeInTheDocument()
    await user.click(within(nav()).getByRole('link', { name: 'Roulette' }))
    expect(await within(table()).findByText('Roulette is not available on this server yet.')).toBeInTheDocument()
    expect(within(table()).getByRole('link', { name: 'wallet and its history' })).toHaveAttribute('href', '/wallet')
    expect(posts(api)).toHaveLength(1)
  })

  it('a schema version below 9 (from the platform status the app already loads) shows it up front, without any spin request', async () => {
    const api = backend({ schemaVersion: '7' })
    renderAt('/')
    const lobbyCard = await screen.findByRole('region', { name: 'European roulette' })
    expect(await within(lobbyCard).findByText('Not available on this server yet')).toBeInTheDocument()

    await userEvent.setup().click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Roulette' }))

    expect(await within(table()).findByText('Roulette is not available on this server yet.')).toBeInTheDocument()
    expect(posts(api)).toHaveLength(0)
  })

  it('a schema version of 9 claims nothing: the table stays open and the lobby makes no availability claim', async () => {
    backend({ schemaVersion: '9' })
    renderAt('/')
    const lobbyCard = await screen.findByRole('region', { name: 'European roulette' })
    await screen.findByText('9')

    expect(within(lobbyCard).queryByText(/Not available/)).not.toBeInTheDocument()
    expect(within(lobbyCard).queryByText(/play now|available/i)).not.toBeInTheDocument()
  })
})

describe('W5-08: responsible presentation', () => {
  it('a win and a loss use the same layout and weight; no celebration, pressure or repeat control; disclaimers visible', async () => {
    const lost: RouletteSpinResult = {
      ...spinResult,
      roundId: 'bbbbbbbb-8b3d-4c55-9a7e-2f0d5b1e9c41',
      winningNumber: 2,
      bets: spinResult.bets.map((bet) => ({ ...bet, payout: 0 })),
      totalPayout: 0,
      balance: 970,
    }
    backend({ spin: [() => jsonResponse(spinResult), () => jsonResponse(lost)], wallet: () => jsonResponse(startingWallet) })
    await openRoulette()
    const user = userEvent.setup()

    await placeExampleBets(user)
    await reviewAndConfirm(user)
    const won = await screen.findByText('Won')
    const lostStyle = (await screen.findByText('Lost')).className
    expect(won.className).toBe(lostStyle)
    /** The result's markup without the server's values (numbers, signs, words, round id) and React ids. */
    const shape = () =>
      screen
        .getByRole('region', { name: 'Result' })
        .innerHTML.replace(/ id="[^"]*"/g, '')
        .replace(/<span class="font-mono">[^<]*<\/span>/, '')
        .replace(/[\d,+−]|Won|Lost|Black|Red|Green/g, '')
    const winShape = shape()

    await placeExampleBets(user)
    await reviewAndConfirm(user)
    expect(await screen.findByText('2 · Black')).toBeInTheDocument()
    // Same markup and classes for a win and a loss, apart from the server's values.
    expect(shape()).toBe(winShape)

    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/!|congrat|lucky|jackpot|hot streak|big win|last chance|hurry|so close|almost won|bonus/i)
    expect(screen.queryByRole('button', { name: /repeat|again|rebet|auto/i })).not.toBeInTheDocument()
    expect(screen.getByText(/virtual coins only; they have no monetary value/)).toBeInTheDocument()
    expect(screen.getByText('Virtual currency only')).toBeInTheDocument()
  })
})

describe('Leaving the page, and other users', () => {
  it('leaving during the request: the answer is still applied once (balance, record), no second request', async () => {
    const spin = controlled()
    const api = backend({ spin: spin.handler, wallet: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 1330, updatedAt: null })] })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    await waitFor(() => expect(spin.count()).toBe(1))
    const nav = () => screen.getByRole('navigation', { name: 'Main' })

    await user.click(within(nav()).getByRole('link', { name: 'Wallet' }))
    await screen.findByRole('heading', { level: 1, name: 'Wallet' }).catch(() => undefined)
    await spin.respond(0, jsonResponse(spinResult))

    expect(await screen.findByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(stored()).toBeNull()
    await user.click(within(nav()).getByRole('link', { name: 'Roulette' }))
    await screen.findByRole('region', { name: 'Place your bets' })
    expect(within(slip()).queryByRole('heading', { name: 'Spin not confirmed' })).not.toBeInTheDocument()
    expect(posts(api)).toHaveLength(1)
    expect(fakeWheel.instances.flatMap((instance) => instance.spinTo.mock.calls)).toEqual([])
  })

  it('coming back while the request is in flight shows "Waiting for the server" and then the result, without a second request', async () => {
    const spin = controlled()
    const api = backend({ spin: spin.handler })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    await waitFor(() => expect(spin.count()).toBe(1))
    const nav = () => screen.getByRole('navigation', { name: 'Main' })

    await user.click(within(nav()).getByRole('link', { name: 'Lobby' }))
    await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })
    await user.click(within(nav()).getByRole('link', { name: 'Roulette' }))

    expect(await within(slip()).findByRole('heading', { name: 'Waiting for the server' })).toBeInTheDocument()
    expect(slipButton('Waiting for the server…')).toHaveAttribute('aria-disabled', 'true')
    await user.click(slipButton('Waiting for the server…'))
    expect(posts(api)).toHaveLength(1)

    await spin.respond(0, jsonResponse(spinResult))

    expect(await screen.findByText('17 · Black')).toBeInTheDocument()
    expect(fakeWheel.live()[0]?.spinTo).toHaveBeenCalledWith(17)
    expect(posts(api)).toHaveLength(1)
  })

  it('leaving during the animation: the round stays confirmed, nothing is sent again, the late wheel end changes nothing', async () => {
    const api = backend()
    await openRoulette()
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    let finishTurn: () => void = () => undefined
    fakeWheel.live()[0]?.spinTo.mockReturnValue(new Promise<void>((resolve) => (finishTurn = resolve)))
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    expect(await screen.findByText(/The wheel is turning to the number it drew/)).toBeInTheDocument()
    const nav = () => screen.getByRole('navigation', { name: 'Main' })

    await user.click(within(nav()).getByRole('link', { name: 'Lobby' }))
    await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })
    await act(async () => finishTurn())

    expect(await screen.findByRole('link', { name: 'Balance 1,330 coins' })).toBeInTheDocument()
    expect(stored()).toBeNull()
    expect(fakeWheel.instances[0]?.destroy).toHaveBeenCalledTimes(1)
    await user.click(within(nav()).getByRole('link', { name: 'Roulette' }))
    await screen.findByRole('region', { name: 'Place your bets' })
    expect(within(slip()).queryByRole('heading', { name: /Spin not confirmed|Waiting for the server/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/The wheel is turning/)).not.toBeInTheDocument()
    expect(fakeWheel.live()[0]?.spinTo).not.toHaveBeenCalled()
    expect(posts(api)).toHaveLength(1)
  })

  it('a late answer for Alice after she signed out never changes Bob’s wallet or page', async () => {
    const spin = controlled()
    let bobSignedIn = false
    const api = backend({
      spin: spin.handler,
      wallet: () => jsonResponse(bobSignedIn ? { balance: 500, updatedAt: '2026-11-25T08:00:00Z' } : startingWallet),
    })
    await openRoulette()
    const user = userEvent.setup()
    await placeExampleBets(user)
    await reviewAndConfirm(user)
    await waitFor(() => expect(spin.count()).toBe(1))

    await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('button', { name: 'Sign out' }))
    await user.click(await within(screen.getByRole('navigation', { name: 'Account' })).findByRole('link', { name: 'Sign in' }))
    bobSignedIn = true
    await user.type(await screen.findByLabelText('Email'), bob.email)
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await user.click(within(await screen.findByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Roulette' }))
    await screen.findByRole('link', { name: 'Balance 500 coins' })

    await spin.respond(0, jsonResponse(spinResult))

    await act(async () => undefined)
    expect(headerBalance()).toHaveAccessibleName('Balance 500 coins')
    expect(screen.queryByText('17 · Black')).not.toBeInTheDocument()
    expect(within(slip()).getByText(/No bets yet/)).toBeInTheDocument()
    // Alice's attempt is resolved in her own record; Bob's page sent nothing.
    expect(stored(alice.id)).toBeNull()
    expect(stored(bob.id)).toBeNull()
    expect(posts(api)).toHaveLength(1)
  })
})
