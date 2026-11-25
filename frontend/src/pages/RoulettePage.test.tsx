import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { AuthContext, type AuthContextValue } from '../features/auth/authContext'
import { fakeWheel } from '../test/fakeWheelGame'
import { alice, PASSWORD, startingWallet, upStatus } from '../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, problemResponse, stubApi } from '../test/http'
import { renderApp } from '../test/renderApp'
import type { UserProfile } from '../types/user'
import { RoulettePage } from './RoulettePage'

const CURRENT_USER = 'GET /api/v1/users/me'
const WALLET = 'GET /api/v1/wallet'

const bob: UserProfile = { ...alice, id: '7c1d2e3f-4a5b-4c6d-9e8f-0a1b2c3d4e5f', email: 'bob@example.com', displayName: 'Bob' }

type Route = Parameters<typeof stubApi>[0][string]

/** No roulette route: a spin request (or any other unexpected request) fails the test. */
function backend({ wallet = () => jsonResponse(startingWallet) }: { wallet?: Route } = {}) {
  return stubApi({
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    [CURRENT_USER]: () => jsonResponse(alice),
    [WALLET]: wallet,
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      return noContent()
    },
    'POST /api/v1/auth/login': (init) => {
      deleteCookie('XSRF-TOKEN')
      const { email } = JSON.parse(String(init.body)) as { email: string }
      return jsonResponse(email === bob.email ? bob : alice)
    },
  })
}

const table = () => screen.getByRole('region', { name: 'Place your bets' })
const slip = () => screen.getByRole('region', { name: 'Bet slip' })
const status = () => within(slip()).getByRole('status')
const reviewButton = () => within(slip()).getByRole('button', { name: 'Review bets' })
const cell = (name: RegExp) => within(table()).getByRole('button', { name })
const slipRows = () =>
  within(slip())
    .queryAllByRole('listitem')
    .map((row) => row.textContent)

async function openTable() {
  const api = backend()
  renderApp('/roulette')
  await screen.findByRole('region', { name: 'Place your bets' })
  await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,000 coins')
  return { api, user: userEvent.setup() }
}

describe('/roulette: the wheel, the table, the chips, the slip and the balance', () => {
  it('shows all parts, explains that the server draws the number, and sends only the shared reads', async () => {
    const { api } = await openTable()

    expect(screen.getByRole('heading', { level: 1, name: 'European roulette' })).toBeInTheDocument()
    expect(within(table()).getByText(/review and confirm them\. The server draws the number and settles your virtual coins\./)).toBeInTheDocument()
    expect(within(table()).getByRole('group', { name: 'Chip value' })).toBeInTheDocument()
    expect(within(table()).getByRole('group', { name: 'Numbers' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'The wheel' })).toBeInTheDocument()
    expect(within(slip()).getByText(/No bets yet/)).toBeInTheDocument()
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    expect(api.requests.map((request) => `${request.method} ${request.path}`).sort()).toEqual([CURRENT_USER, WALLET].sort())
  })

  it('offers no spin or confirmation control before "Review bets" (W5-07: confirmation is the second step)', async () => {
    await openTable()
    expect(screen.queryByRole('button', { name: /spin|confirm/i })).not.toBeInTheDocument()
  })
})

describe('placing bets (AC-W5-06.3, 06.4)', () => {
  it('places the chosen chip with the mouse; a second chip on the same cell adds to the one bet', async () => {
    const { user } = await openTable()

    await user.click(screen.getByRole('radio', { name: '25 coins' }))
    await user.click(cell(/^17, black/))
    await user.click(cell(/^17, black/))

    expect(cell(/^17, black/)).toHaveAccessibleName('17, black. Straight bet, pays 35 to 1. 50 coins placed.')
    expect(cell(/^17, black/)).toHaveTextContent('1750')
    const rows = within(slip()).getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(within(rows[0] as HTMLElement).getByText('Number 17')).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('Straight bet · pays 35 to 1')
    expect(within(rows[0] as HTMLElement).getByText('50 coins')).toBeInTheDocument()
    expect(within(rows[0] as HTMLElement).getByRole('button', { name: 'Remove Number 17, 50 coins' })).toBeInTheDocument()
    expect(status()).toHaveTextContent('Number 17: 50 coins. 1 bet, total 50 coins.')
  })

  it('works with the keyboard only: arrows choose the chip, Tab reaches the cell, Enter and Space place it', async () => {
    const { user } = await openTable()

    screen.getByRole('radio', { name: '10 coins' }).focus()
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByRole('radio', { name: '5 coins' })).toBeChecked()
    await user.tab()
    expect(cell(/^0, green/)).toHaveFocus()
    await user.keyboard('{Enter}')
    await user.keyboard(' ')

    expect(cell(/^0, green/)).toHaveAccessibleName('0, green. Straight bet, pays 35 to 1. 10 coins placed.')
    expect(status()).toHaveTextContent('Number 0: 10 coins. 1 bet, total 10 coins.')
  })

  it('places dozens and every even-money selection with their odds in the slip, in placement order', async () => {
    const { user } = await openTable()

    for (const name of [/^2nd dozen/, /^Red\./, /^Black\./, /^Even\./, /^Odd\./, /^Low,/, /^High,/]) await user.click(cell(name))

    expect(slipRows().map((row) => row?.replace(/10 coins.*$/, ''))).toEqual([
      '2nd dozen (13–24)Dozen bet · pays 2 to 1',
      'RedRed or black bet · pays 1 to 1',
      'BlackRed or black bet · pays 1 to 1',
      'EvenEven or odd bet · pays 1 to 1',
      'OddEven or odd bet · pays 1 to 1',
      'Low (1–18)Low or high bet · pays 1 to 1',
      'High (19–36)Low or high bet · pays 1 to 1',
    ])
    expect(within(slip()).getByText('7 of 10')).toBeInTheDocument()
    expect(within(slip()).getByText('70 coins')).toBeInTheDocument()
  })

  it('caps a bet at 1,000 coins and announces the cap and the refusal', async () => {
    const { user } = await openTable()

    await user.click(screen.getByRole('radio', { name: '500 coins' }))
    await user.click(cell(/^Red\./))
    await user.click(cell(/^Red\./))
    expect(status()).toHaveTextContent('Red: 1,000 coins. 1 bet, total 1,000 coins.')
    await user.click(cell(/^Red\./))

    expect(status()).toHaveTextContent('Not added: Red already has 1,000 coins, the maximum per bet.')
    expect(cell(/^Red\./)).toHaveAccessibleName('Red. Red or black bet, pays 1 to 1. 1,000 coins placed.')
  })

  it('refuses an eleventh selection, announces it, and keeps the ten bets', async () => {
    const { user } = await openTable()
    await user.click(screen.getByRole('radio', { name: '1 coin' }))

    for (let number = 1; number <= 10; number += 1) await user.click(cell(new RegExp(`^${number}, `)))
    await user.click(cell(/^11, /))

    expect(status()).toHaveTextContent('Not added: up to 10 different bets per spin. Remove a bet to choose Number 11.')
    expect(slipRows()).toHaveLength(10)
    expect(cell(/^11, /)).toHaveAccessibleName('11, black. Straight bet, pays 35 to 1.')
  })
})

describe('the slip (AC-W5-06.5)', () => {
  it('removes one bet and clears all; the total is the sum of the slip', async () => {
    const { user } = await openTable()
    await user.click(cell(/^1, red/))
    await user.click(screen.getByRole('radio', { name: '100 coins' }))
    await user.click(cell(/^Black\./))
    expect(within(slip()).getByText('110 coins')).toBeInTheDocument()

    await user.click(within(slip()).getByRole('button', { name: 'Remove Black, 100 coins' }))

    expect(slipRows()).toHaveLength(1)
    expect(within(slip()).getByText('1 of 10')).toBeInTheDocument()
    expect(status()).toHaveTextContent('Black removed. 1 bet, total 10 coins.')
    expect(cell(/^Black\./)).toHaveAccessibleName('Black. Red or black bet, pays 1 to 1.')

    await user.click(within(slip()).getByRole('button', { name: 'Clear all bets' }))

    expect(slipRows()).toEqual([])
    expect(status()).toHaveTextContent('All bets cleared.')
    expect(within(slip()).getByText(/No bets yet/)).toBeInTheDocument()
    expect(within(slip()).queryByRole('button', { name: 'Clear all bets' })).not.toBeInTheDocument()
  })

  it('"Review bets" is unavailable with a reason while the slip is empty', async () => {
    const { user } = await openTable()

    expect(reviewButton()).toHaveAttribute('aria-disabled', 'true')
    expect(reviewButton()).toHaveAccessibleDescription('Place at least one bet to review it.')
    await user.click(reviewButton())
    expect(screen.queryByRole('heading', { name: 'Review your bets' })).not.toBeInTheDocument()
  })

  it('"Review bets" is unavailable with a reason when the known balance is lower than the total', async () => {
    backend({ wallet: () => jsonResponse({ balance: 600, updatedAt: '2026-11-25T08:00:00Z' }) })
    renderApp('/roulette')
    const user = userEvent.setup()
    await within(await screen.findByRole('region', { name: 'Your balance' })).findByText('600 coins')

    await user.click(screen.getByRole('radio', { name: '500 coins' }))
    await user.click(cell(/^Red\./))
    expect(reviewButton()).not.toHaveAttribute('aria-disabled')
    await user.click(cell(/^Odd\./))

    const reason = 'Your balance is too low for these bets: 1,000 coins in bets, 600 coins available.'
    expect(reviewButton()).toHaveAttribute('aria-disabled', 'true')
    expect(reviewButton()).toHaveAccessibleDescription(reason)
    expect(within(slip()).getByText(reason)).toBeVisible()
    await user.click(reviewButton())
    expect(screen.queryByRole('heading', { name: 'Review your bets' })).not.toBeInTheDocument()

    await user.click(within(slip()).getByRole('button', { name: 'Remove Odd, 500 coins' }))
    expect(reviewButton()).not.toHaveAttribute('aria-disabled')
  })

  it('does not treat an unknown balance as zero: the review stays available and the slip says the server checks it', async () => {
    backend({ wallet: () => problemResponse(500, 'INTERNAL_ERROR') })
    renderApp('/roulette')
    const user = userEvent.setup()
    await within(await screen.findByRole('region', { name: 'Your balance' })).findByText('Balance unavailable')

    await user.click(screen.getByRole('radio', { name: '500 coins' }))
    await user.click(cell(/^Red\./))
    await user.click(cell(/^Black\./))

    expect(within(slip()).getByText(/Your balance is not known right now\. The server checks it when a spin is sent\./)).toBeInTheDocument()
    expect(reviewButton()).not.toHaveAttribute('aria-disabled')
    expect(reviewButton()).not.toHaveAccessibleDescription(/too low/)
  })

  it('shows no potential win or payout anywhere: only stakes and odds', async () => {
    const { user } = await openTable()
    await user.click(cell(/^17, black/))
    await user.click(cell(/^3rd dozen/))

    expect(screen.queryByText(/win|payout|return|profit/i)).not.toBeInTheDocument()
    expect(within(slip()).queryByText('360 coins')).not.toBeInTheDocument()
    expect(within(slip()).getByText('20 coins')).toBeInTheDocument()
  })
})

describe('the review (the confirmation step of W5-07)', () => {
  it('opens with the focus on its heading, locks the table with a reason, sends nothing until confirmed, and goes back unchanged', async () => {
    const { api, user } = await openTable()
    await user.click(cell(/^17, black/))
    await user.click(cell(/^Red\./))

    await user.click(reviewButton())

    const heading = within(slip()).getByRole('heading', { name: 'Review your bets' })
    expect(heading).toHaveFocus()
    expect(slipRows()).toHaveLength(2)
    expect(within(slip()).queryByRole('button', { name: /^Remove/ })).not.toBeInTheDocument()
    expect(within(slip()).queryByRole('button', { name: 'Clear all bets' })).not.toBeInTheDocument()
    expect(within(slip()).getByText(/Your balance:/)).toHaveTextContent('Your balance: 1,000 coins')
    expect(within(slip()).getByText('The server draws the number. Your stake is debited when you confirm.')).toBeInTheDocument()
    expect(within(slip()).getByRole('button', { name: 'Confirm and spin' })).toBeInTheDocument()
    expect(api.requests.filter((request) => request.method !== 'GET')).toEqual([])

    const locked = cell(/^Black\./)
    expect(locked).toHaveAttribute('aria-disabled', 'true')
    expect(locked).toHaveAccessibleDescription(/Your bets are being reviewed/)
    await user.click(locked)
    expect(slipRows()).toHaveLength(2)

    await user.click(within(slip()).getByRole('button', { name: 'Back to bets' }))

    expect(reviewButton()).toHaveFocus()
    expect(slipRows()).toHaveLength(2)
    expect(cell(/^Black\./)).not.toHaveAttribute('aria-disabled')
    expect(api.requests.filter((request) => request.method !== 'GET')).toEqual([])
  })
})

describe('nothing is sent and nothing changes the wallet without "Confirm and spin"', () => {
  it('after placing, removing, reviewing and clearing: no spin request, one wallet read, the balance unchanged, the wheel idle', async () => {
    const { api, user } = await openTable()

    await user.click(cell(/^5, red/))
    await user.click(cell(/^1st dozen/))
    await user.click(within(slip()).getByRole('button', { name: 'Remove Number 5, 10 coins' }))
    await user.click(reviewButton())
    await user.click(within(slip()).getByRole('button', { name: 'Back to bets' }))
    await user.click(within(slip()).getByRole('button', { name: 'Clear all bets' }))

    expect(api.requests.map((request) => `${request.method} ${request.path}`).sort()).toEqual([CURRENT_USER, WALLET].sort())
    expect(within(screen.getByRole('region', { name: 'Your balance' })).getByText('1,000 coins')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Balance 1,000 coins' })).toBeInTheDocument()
    expect(fakeWheel.instances).toHaveLength(1)
    expect(fakeWheel.instances[0]?.spinTo).not.toHaveBeenCalled()
  })
})

describe('summary bar for narrow screens', () => {
  it('appears with the first bet, repeats the count and total, and its "Review bets" has the same reason', async () => {
    backend({ wallet: () => jsonResponse({ balance: 5, updatedAt: '2026-11-25T08:00:00Z' }) })
    renderApp('/roulette')
    const user = userEvent.setup()
    await within(await screen.findByRole('region', { name: 'Your balance' })).findByText('5 coins')
    expect(screen.getAllByRole('button', { name: 'Review bets' })).toHaveLength(1)

    await user.click(cell(/^Even\./))

    const buttons = screen.getAllByRole('button', { name: 'Review bets' })
    expect(buttons).toHaveLength(2)
    const bar = buttons[1]?.closest('div.sticky') as HTMLElement
    expect(bar).toHaveTextContent('1 bet · 10 coins')
    expect(buttons[1]).toHaveAttribute('aria-disabled', 'true')
    expect(buttons[1]).toHaveAccessibleDescription(/Your balance is too low for these bets/)
    // The bar repeats the last message for sighted players only; the slip's live region announces it.
    expect(within(bar).getByText('Even: 10 coins. 1 bet, total 10 coins.')).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('session isolation', () => {
  it('never shows one player’s local bets to the next account', async () => {
    const { user } = await openTable()
    await user.click(cell(/^Red\./))
    expect(slipRows()).toHaveLength(1)

    await user.click(within(screen.getByRole('navigation', { name: 'Account' })).getByRole('button', { name: 'Sign out' }))
    await user.click(await within(screen.getByRole('navigation', { name: 'Account' })).findByRole('link', { name: 'Sign in' }))
    await user.type(await screen.findByLabelText('Email'), bob.email)
    await user.type(screen.getByLabelText('Password'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await within(await screen.findByRole('navigation', { name: 'Main' })).findByText('Roulette')
    await user.click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Roulette' }))

    await screen.findByRole('region', { name: 'Place your bets' })
    expect(within(screen.getByRole('navigation', { name: 'Account' })).getByText('Bob')).toBeInTheDocument()
    expect(slipRows()).toEqual([])
    expect(cell(/^Red\./)).toHaveAccessibleName('Red. Red or black bet, pays 1 to 1.')
  })

  it('drops the local bets when the player changes while the page stays mounted (not relying on the unmount)', async () => {
    stubApi({})
    const tree = (user: UserProfile) => (
      <AuthContext value={{ state: { status: 'authenticated', user } } as AuthContextValue}>
        <RoulettePage />
      </AuthContext>
    )
    const view = render(tree(alice))
    const user = userEvent.setup()
    await user.click(cell(/^Red\./))
    expect(slipRows()).toHaveLength(1)

    view.rerender(tree(bob))

    expect(slipRows()).toEqual([])
    expect(cell(/^Red\./)).toHaveAccessibleName('Red. Red or black bet, pays 1 to 1.')
  })
})
