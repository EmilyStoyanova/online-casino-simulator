import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { alice, historyAfterSpin, PASSWORD, startingWallet, upStatus } from '../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, problemResponse, stubApi } from '../test/http'
import { renderApp } from '../test/renderApp'
import type { UserProfile } from '../types/user'
import type { WalletTransaction, WalletTransactionPage } from '../types/wallet'

const FIRST = 'GET /api/v1/wallet/transactions?limit=20'
const WALLET = 'GET /api/v1/wallet'
/** An opaque cursor with characters that must be encoded in the query and come back unchanged. */
const CURSOR = 'eyJpZCI6MjF9+/=='
const OLDER = `GET /api/v1/wallet/transactions?limit=20&cursor=${encodeURIComponent(CURSOR)}`

const bob: UserProfile = { ...alice, id: '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f', email: 'bob@example.com', displayName: 'Bob' }

type Route = Parameters<typeof stubApi>[0][string]
type Handler = Exclude<Route, readonly unknown[]>

/** `count` roulette entries with ids from `newest` down (newest first), 20 Nov 2026 10:15 UTC. */
function entries(newest: number, count: number): WalletTransaction[] {
  return Array.from({ length: count }, (_, index) => {
    const id = newest - index
    return id % 2 === 0
      ? { id, type: 'ROULETTE_PAYOUT', amount: 20, balanceAfter: 1000 + id, createdAt: '2026-11-20T10:15:30Z' }
      : { id, type: 'ROULETTE_STAKE', amount: -10, balanceAfter: 1000 + id, createdAt: '2026-11-20T10:15:30Z' }
  })
}

const page = (items: WalletTransaction[], nextCursor: string | null = null): WalletTransactionPage => ({ items, nextCursor })
/** A full first page (ids 40…21) with a cursor, and the older rest (ids 20…6, the last page). */
const fullFirstPage = page(entries(40, 20), CURSOR)
const lastPage = page(entries(20, 15))

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
  first = () => jsonResponse(historyAfterSpin),
  older = () => jsonResponse(lastPage),
  wallet = () => jsonResponse(startingWallet),
  extra = {},
}: { first?: Route; older?: Route; wallet?: Route; extra?: Record<string, Route> } = {}) {
  return stubApi({
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/users/me': () => jsonResponse(alice),
    [WALLET]: wallet,
    [FIRST]: first,
    [OLDER]: older,
    ...extra,
  })
}

const history = () => screen.getByRole('region', { name: 'Transaction history' })
const table = () => within(history()).getByRole('table', { name: 'Wallet entries, newest first' })
const loadOlder = () => within(history()).queryByRole('button', { name: /Load older entries|Loading older entries…/ })

/** What a screen reader gets from a cell: its text without the `aria-hidden` card label. */
function spoken(element: Element): string {
  const copy = element.cloneNode(true) as Element
  copy.querySelectorAll('[aria-hidden="true"]').forEach((hidden) => hidden.remove())
  return (copy.textContent ?? '').trim()
}

/** The data rows, each as the four cells a screen reader announces. */
function rows(): string[][] {
  return within(table())
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map(spoken))
}

const rowIds = () => rows().map((cells) => Number(cells[3]?.replace(/\D/g, '')) - 1000)

async function shown() {
  return within(await screen.findByRole('region', { name: 'Transaction history' })).findByRole('table')
}

beforeEach(() => {
  // Timestamps are shown in the browser's local time zone; Sofia is UTC+2 in November.
  vi.stubEnv('TZ', 'Europe/Sofia')
})

describe('AC-W5-05.1: the first page with time, type, signed amount and balance after', () => {
  it('requests the first 20 entries once, without a CSRF token, and shows each entry in local time', async () => {
    const api = backend()
    renderApp('/wallet')
    await shown()

    expect(rows()).toEqual([
      ['20 Nov 2026, 12:15', 'Roulette payout', '+360 coins', '1,330 coins'],
      ['20 Nov 2026, 12:15', 'Roulette stake', '−30 coins', '970 coins'],
      ['12 Nov 2026, 11:15', 'Starting balance', '+1,000 coins', '1,000 coins'],
    ])
    expect(api.requestsTo(FIRST)).toHaveLength(1)
    expect(api.requestsTo(FIRST)[0]?.headers).toEqual({ Accept: 'application/json, application/problem+json' })
    // The machine-readable time is the server's value, unchanged.
    expect(within(table()).getAllByText(/Nov 2026/)[0]?.closest('time')).toHaveAttribute('datetime', '2026-11-20T10:15:30.118204Z')
  })

  it('shows a full first page of 20 entries, newest first', async () => {
    backend({ first: () => jsonResponse(fullFirstPage) })
    renderApp('/wallet')
    await shown()

    expect(rowIds()).toEqual(fullFirstPage.items.map((item) => item.id))
    expect(rows()).toHaveLength(20)
  })

  it('shows types added by a later contract version as "Other entry", with the sign of the amount', async () => {
    backend({
      first: () =>
        jsonResponse(
          page([
            { id: 9, type: 'LEARNING_REWARD', amount: 50, balanceAfter: 1050, createdAt: '2026-11-21T08:00:00Z' },
            { id: 8, type: 'ADMIN_CORRECTION', amount: -5, balanceAfter: 1000, createdAt: '2026-11-21T07:00:00Z' },
          ]),
        ),
    })
    renderApp('/wallet')
    await shown()

    expect(rows()).toEqual([
      ['21 Nov 2026, 10:00', 'Other entry', '+50 coins', '1,050 coins'],
      ['21 Nov 2026, 09:00', 'Other entry', '−5 coins', '1,000 coins'],
    ])
  })

  it('shows whole virtual coins only, never a currency', async () => {
    backend()
    renderApp('/wallet')
    await shown()

    expect(history().textContent).not.toMatch(/[$€£¥]|BGN|EUR|USD|\d\.\d/)
  })
})

describe('AC-W5-05.2: "Load older entries" with the opaque cursor', () => {
  it('sends the previous nextCursor unchanged, appends the older entries and disappears on the last page', async () => {
    const api = backend({ first: () => jsonResponse(fullFirstPage) })
    renderApp('/wallet')
    await shown()
    expect(within(history()).getByRole('status')).toHaveTextContent('Showing the 20 entries loaded so far. Older entries are available.')

    await userEvent.setup().click(loadOlder() as HTMLElement)

    await waitFor(() => expect(rows()).toHaveLength(35))
    const sent = new URL(api.requestsTo(OLDER)[0]?.path ?? '', 'http://localhost')
    expect(sent.searchParams.get('cursor')).toBe(CURSOR)
    expect(sent.searchParams.get('limit')).toBe('20')
    expect(rowIds()).toEqual([...fullFirstPage.items, ...lastPage.items].map((item) => item.id))
    expect(loadOlder()).not.toBeInTheDocument()
    expect(within(history()).getByRole('status')).toHaveTextContent('Showing 35 entries: your complete history.')
  })

  it('offers no "Load older entries" when the first page is the last one', async () => {
    backend()
    renderApp('/wallet')
    await shown()

    expect(loadOlder()).not.toBeInTheDocument()
  })

  it('sends one request at a time: the button is disabled while older entries load', async () => {
    const older = controlled()
    const api = backend({ first: () => jsonResponse(fullFirstPage), older: older.handler })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(loadOlder() as HTMLElement)
    await user.click(loadOlder() as HTMLElement)

    expect(loadOlder()).toBeDisabled()
    expect(loadOlder()).toHaveTextContent('Loading older entries…')
    expect(history()).toHaveAttribute('aria-busy', 'true')
    expect(within(history()).getByRole('status')).toHaveTextContent('Loading older entries…')
    expect(rows()).toHaveLength(20)
    expect(api.requestsTo(OLDER)).toHaveLength(1)
    await older.respond(0, jsonResponse(lastPage))
    expect(rows()).toHaveLength(35)
  })

  it('keeps the loaded entries when an older page fails, and its retry sends the same cursor', async () => {
    const api = backend({
      first: () => jsonResponse(fullFirstPage),
      older: [() => problemResponse(500, 'INTERNAL_ERROR'), () => jsonResponse(lastPage)],
    })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(loadOlder() as HTMLElement)

    const alert = await within(history()).findByRole('alert')
    expect(alert).toHaveTextContent('Older entries could not be loaded. The entries above are still shown.')
    expect(rows()).toHaveLength(20)
    expect(loadOlder()).not.toBeInTheDocument()

    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(rows()).toHaveLength(35))
    expect(api.requestsTo(OLDER)).toHaveLength(2)
    expect(within(history()).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('W5-08: after the last older page the focus moves to the first new entry (the button is gone)', async () => {
    backend({ first: () => jsonResponse(fullFirstPage) })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(loadOlder() as HTMLElement)

    await waitFor(() => expect(rows()).toHaveLength(35))
    const firstNew = within(table()).getAllByRole('row')[21]
    expect(firstNew).toHaveFocus()
    expect(spoken(firstNew as Element)).toContain('Roulette payout')
    expect(loadOlder()).not.toBeInTheDocument()
  })

  it('W5-08: while more pages exist the focus stays on "Load older entries"; after a failure it is on "Try again"', async () => {
    const SECOND = 'eyJpZCI6Nn0'
    backend({
      first: () => jsonResponse(fullFirstPage),
      older: [() => jsonResponse(page(entries(20, 5), SECOND))],
      extra: { [`GET /api/v1/wallet/transactions?limit=20&cursor=${SECOND}`]: () => problemResponse(500, 'INTERNAL_ERROR') },
    })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(loadOlder() as HTMLElement)
    await waitFor(() => expect(rows()).toHaveLength(25))
    expect(loadOlder()).toHaveFocus()

    await user.keyboard('{Enter}')

    const alert = await within(history()).findByRole('alert')
    expect(within(alert).getByRole('button', { name: 'Try again' })).toHaveFocus()
  })

  it('explains an unreachable server when an older page fails', async () => {
    backend({ first: () => jsonResponse(fullFirstPage), older: () => Promise.reject(new TypeError('Failed to fetch')) })
    renderApp('/wallet')
    await shown()

    await userEvent.setup().click(loadOlder() as HTMLElement)

    expect(await within(history()).findByRole('alert')).toHaveTextContent(/could not be reached.*The entries above are still shown/)
    expect(rows()).toHaveLength(20)
  })
})

describe('AC-W5-05.3: "Refresh history" reloads the first page and the balance', () => {
  it('reloads both, keeps the entries visible meanwhile and shows new entries at the top', async () => {
    const first = controlled()
    const api = backend({
      first: first.handler,
      wallet: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 1330, updatedAt: '2026-11-20T10:15:30Z' })],
    })
    renderApp('/wallet')
    await waitFor(() => expect(first.count()).toBe(1))
    await first.respond(0, jsonResponse(page(historyAfterSpin.items.slice(2))))
    await shown()
    expect(rows()).toHaveLength(1)

    await userEvent.setup().click(within(history()).getByRole('button', { name: 'Refresh history' }))

    expect(within(history()).getByRole('button', { name: 'Refreshing…' })).toBeDisabled()
    expect(history()).toHaveAttribute('aria-busy', 'true')
    expect(rows()).toHaveLength(1)
    await first.respond(1, jsonResponse(historyAfterSpin))

    expect(rows().map((cells) => cells[1])).toEqual(['Roulette payout', 'Roulette stake', 'Starting balance'])
    expect(rows().map((cells) => cells[2])).toEqual(['+360 coins', '−30 coins', '+1,000 coins'])
    expect(api.requestsTo(FIRST)).toHaveLength(2)
    expect(api.requestsTo(WALLET)).toHaveLength(2)
    expect(await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,330 coins')).toBeInTheDocument()
  })

  it('starts the list again from the first page after older pages were loaded', async () => {
    const api = backend({ first: [() => jsonResponse(fullFirstPage), () => jsonResponse(page(entries(41, 20), CURSOR))] })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()
    await user.click(loadOlder() as HTMLElement)
    await waitFor(() => expect(rows()).toHaveLength(35))

    await user.click(within(history()).getByRole('button', { name: 'Refresh history' }))

    await waitFor(() => expect(rowIds()[0]).toBe(41))
    expect(rows()).toHaveLength(20)
    expect(loadOlder()).toHaveTextContent('Load older entries')
    expect(api.requestsTo(FIRST)).toHaveLength(2)
  })

  it('recovers from a cursor the server rejects: the retry fails alike, a refresh starts again from a new cursor', async () => {
    const NEW_CURSOR = 'eyJpZCI6MjJ9'
    const NEWER_OLDER = `GET /api/v1/wallet/transactions?limit=20&cursor=${NEW_CURSOR}`
    const api = backend({
      first: [() => jsonResponse(fullFirstPage), () => jsonResponse(page(entries(41, 20), NEW_CURSOR))],
      // The cursor of the first page is no longer accepted (e.g. after a server update).
      older: () => problemResponse(400, 'VALIDATION_FAILED'),
      extra: { [NEWER_OLDER]: () => jsonResponse(page(entries(21, 16))) },
    })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(loadOlder() as HTMLElement)
    const alert = await within(history()).findByRole('alert')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(api.requestsTo(OLDER)).toHaveLength(2))
    expect(await within(history()).findByRole('alert')).toHaveTextContent('Older entries could not be loaded.')
    expect(rows()).toHaveLength(20)

    await user.click(within(history()).getByRole('button', { name: 'Refresh history' }))

    await waitFor(() => expect(rowIds()[0]).toBe(41))
    expect(within(history()).queryByRole('alert')).not.toBeInTheDocument()
    expect(rows()).toHaveLength(20)
    await user.click(loadOlder() as HTMLElement)
    await waitFor(() => expect(rows()).toHaveLength(36))
    expect(api.requestsTo(NEWER_OLDER)).toHaveLength(1)
    expect(api.requestsTo(OLDER)).toHaveLength(2)
    expect(loadOlder()).not.toBeInTheDocument()
  })

  it('keeps the entries when a refresh fails, says they may be out of date, and retries', async () => {
    backend({
      first: [
        () => jsonResponse(historyAfterSpin),
        () => problemResponse(500, 'INTERNAL_ERROR'),
        () => jsonResponse(page(entries(5, 5))),
      ],
    })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()

    await user.click(within(history()).getByRole('button', { name: 'Refresh history' }))

    const alert = await within(history()).findByRole('alert')
    expect(alert).toHaveTextContent('Your transaction history could not be refreshed, so it may be out of date.')
    expect(rows()).toHaveLength(3)
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect(within(history()).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('replaces a "load older" in flight: the older answer is aborted and never appended', async () => {
    const older = controlled()
    const api = backend({
      first: [() => jsonResponse(fullFirstPage), () => jsonResponse(page(entries(41, 20), CURSOR))],
      older: older.handler,
    })
    renderApp('/wallet')
    await shown()
    const user = userEvent.setup()
    await user.click(loadOlder() as HTMLElement)
    await waitFor(() => expect(older.count()).toBe(1))

    await user.click(within(history()).getByRole('button', { name: 'Refresh history' }))
    await waitFor(() => expect(rowIds()[0]).toBe(41))
    await older.respond(0, jsonResponse(lastPage))

    expect(rows()).toHaveLength(20)
    const olderSignal = api.fetchMock.mock.calls.find(([input]) => `GET ${String(input)}` === OLDER)?.[1]?.signal
    expect(olderSignal?.aborted).toBe(true)
    expect(api.requestsTo(FIRST)).toHaveLength(2)
  })
})

describe('AC-W5-05.4: loading, empty and error states; table and card layout', () => {
  it('announces loading, marks the history busy and shows no entry or number', async () => {
    const first = controlled()
    backend({ first: first.handler })
    renderApp('/wallet')

    const region = await screen.findByRole('region', { name: 'Transaction history' })
    expect(within(region).getByRole('status')).toHaveTextContent('Loading your transaction history…')
    expect(region).toHaveAttribute('aria-busy', 'true')
    expect(within(region).queryByRole('table')).not.toBeInTheDocument()
    expect(within(region).queryByText(/\d coins?/)).not.toBeInTheDocument()

    await waitFor(() => expect(first.count()).toBe(1))
    await first.respond(0, jsonResponse(historyAfterSpin))
    await shown()
    expect(history()).toHaveAttribute('aria-busy', 'false')
  })

  it('announces an empty history, with a refresh but no table and no "Load older entries"', async () => {
    backend({ first: () => jsonResponse(page([])) })
    renderApp('/wallet')

    await screen.findByRole('region', { name: 'Transaction history' })
    await waitFor(() => expect(within(history()).getByRole('status')).toHaveTextContent('No entries yet.'))
    expect(within(history()).queryByRole('table')).not.toBeInTheDocument()
    expect(loadOlder()).not.toBeInTheDocument()
    expect(within(history()).getByRole('button', { name: 'Refresh history' })).toBeEnabled()
  })

  it('shows an error alert when the first page fails, and "Try again" loads it', async () => {
    const api = backend({ first: [() => problemResponse(500, 'INTERNAL_ERROR'), () => jsonResponse(historyAfterSpin)] })
    renderApp('/wallet')

    const alert = await within(await screen.findByRole('region', { name: 'Transaction history' })).findByRole('alert')
    expect(alert).toHaveTextContent('Your transaction history could not be loaded.')
    expect(within(history()).queryByRole('table')).not.toBeInTheDocument()

    await userEvent.setup().click(within(history()).getByRole('button', { name: 'Try again' }))

    await shown()
    expect(rows()).toHaveLength(3)
    expect(api.requestsTo(FIRST)).toHaveLength(2)
  })

  it('explains a server that is starting up', async () => {
    backend({ first: () => new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }) })
    renderApp('/wallet')

    expect(await within(await screen.findByRole('region', { name: 'Transaction history' })).findByRole('alert')).toHaveTextContent(
      /starting up or temporarily unavailable/,
    )
  })

  it('is one table with a caption and column headers; the card labels are hidden from screen readers', async () => {
    backend()
    renderApp('/wallet')
    await shown()

    expect(within(table()).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Time',
      'Type',
      'Amount',
      'Balance after',
    ])
    const firstRow = within(table()).getAllByRole('row')[1] as HTMLElement
    const labels = firstRow.querySelectorAll('td > span[aria-hidden="true"]')
    expect([...labels].map((label) => label.textContent)).toEqual(['Time', 'Type', 'Amount', 'Balance after'])
    // One markup for both layouts: each value exists once, and there is no second (list) version.
    expect(within(history()).getAllByText('+360 coins')).toHaveLength(1)
    expect(within(history()).queryByRole('list')).not.toBeInTheDocument()
    expect(within(history()).getAllByRole('table')).toHaveLength(1)
  })

  it('keeps the virtual-money disclaimer on the page', async () => {
    backend()
    renderApp('/wallet')
    await shown()

    expect(screen.getByText(/Virtual coins only\. They have no monetary value and cannot be bought or paid out\./)).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Your balance' })).getByText('Virtual coins have no monetary value.')).toBeInTheDocument()
  })
})

describe('Session isolation: no history of another session or account', () => {
  /** Backend for two players: the history answers for whoever signed in last. */
  function twoPlayers({ aliceFirst }: { aliceFirst?: Handler } = {}) {
    let current: UserProfile = alice
    const aliceHistory = page(entries(40, 20), CURSOR)
    const bobHistory = page([{ id: 77, type: 'STARTING_BALANCE', amount: 1000, balanceAfter: 1000, createdAt: '2026-11-22T09:00:00Z' }])
    const api = backend({
      first: (init) => (current.id === alice.id ? (aliceFirst ?? (() => jsonResponse(aliceHistory)))(init) : jsonResponse(bobHistory)),
      older: () => problemResponse(401, 'SESSION_EXPIRED'),
      extra: {
        'GET /api/v1/auth/csrf': csrfEndpoint(),
        'POST /api/v1/auth/login': () => {
          deleteCookie('XSRF-TOKEN')
          current = bob
          return jsonResponse(bob)
        },
        'POST /api/v1/auth/logout': () => {
          deleteCookie('XSRF-TOKEN')
          return noContent()
        },
      },
    })
    return { api }
  }

  async function signInAs(email: string) {
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Email'), email)
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
  }

  it('an expired session while loading older entries shows the login page; the next account sees only its own history', async () => {
    twoPlayers()
    renderApp('/wallet')
    await shown()

    await userEvent.setup().click(loadOlder() as HTMLElement)

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    // No amount of Alice's is left on the page (neither history nor balance).
    expect(screen.queryAllByText(/\d[\d,]* coins?$/)).toEqual([])

    await signInAs(bob.email)

    await shown()
    expect(rows()).toEqual([['22 Nov 2026, 11:00', 'Starting balance', '+1,000 coins', '1,000 coins']])
  })

  it('a late answer for the previous player is never shown to the next one', async () => {
    const aliceFirst = controlled()
    const { api } = twoPlayers({ aliceFirst: aliceFirst.handler })
    renderApp('/wallet')
    await waitFor(() => expect(aliceFirst.count()).toBe(1))
    const user = userEvent.setup()

    await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('button', { name: 'Sign out' }))
    await screen.findByText(/You are signed out\./)
    await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('link', { name: 'Sign in' }))
    await signInAs(bob.email)
    await user.click(await screen.findByRole('link', { name: 'Wallet' }))
    await shown()
    await aliceFirst.respond(0, jsonResponse(page(entries(40, 20), CURSOR)))

    expect(rows()).toEqual([['22 Nov 2026, 11:00', 'Starting balance', '+1,000 coins', '1,000 coins']])
    const aliceSignal = api.fetchMock.mock.calls.find(([input]) => `GET ${String(input)}` === FIRST)?.[1]?.signal
    expect(aliceSignal?.aborted).toBe(true)
  })
})
