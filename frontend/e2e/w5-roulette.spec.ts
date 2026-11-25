/**
 * W5 browser scenario (docs/plans/week-05.md §11, §16.3): the roulette page against the real backend
 * and PostgreSQL of the isolated W2-16 stack. Nothing is mocked and no number is forced. The second
 * test lets the spin reach the real server and only drops its response on the way back to the page.
 */
import { expect, test, type Page, type Request } from '@playwright/test'
import { newAccount } from './support/accounts'
import { queryDatabase } from './support/stack'

interface SettledBet {
  category: string
  number?: number
  color?: string
  parity?: string
  range?: string
  dozen?: string
  amount: number
  payout: number
}
interface SpinResult {
  roundId: string
  winningNumber: number
  winningColor: string
  bets: SettledBet[]
  totalStake: number
  totalPayout: number
  balance: number
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const RED = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36])
const coins = (n: number) => `${new Intl.NumberFormat('en-GB').format(n)} ${n === 1 ? 'coin' : 'coins'}`
const isSpin = (url: string) => url.endsWith('/api/v1/games/roulette/spins')
/** "Balance after this round" and its value are separate elements, so there may be no space between them. */
const balanceAfter = (balance: number) => new RegExp(`Balance after this round\\s*${coins(balance)}`)

/** The payout of a settled bet by the published rules: an independent check of the server's settlement. */
function payoutByRules(bet: SettledBet, n: number): number {
  const wins: Record<string, boolean> = {
    STRAIGHT: bet.number === n,
    RED_BLACK: n !== 0 && (bet.color === 'RED') === RED.has(n),
    EVEN_ODD: n !== 0 && (bet.parity === 'EVEN') === (n % 2 === 0),
    LOW_HIGH: n !== 0 && (bet.range === 'LOW') === n <= 18,
    DOZEN: n !== 0 && Math.ceil(n / 12) === ({ FIRST: 1, SECOND: 2, THIRD: 3 } as Record<string, number>)[bet.dozen ?? ''],
  }
  const odds: Record<string, number> = { STRAIGHT: 35, RED_BLACK: 1, EVEN_ODD: 1, LOW_HIGH: 1, DOZEN: 2 }
  return wins[bet.category] ? bet.amount * (odds[bet.category] + 1) : 0
}

/**
 * Registers a disposable player through the real form (IPv4, through the Vite proxy) and ends signed in.
 * Not `createAccount`: in a full run the W2-16 suite uses all 10 registrations per hour of the IPv6
 * fixture address before this file starts, while the browser's address has room left.
 */
async function registerInBrowser(page: Page, label: string): Promise<void> {
  const account = newAccount(label)
  await page.goto('/register')
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Display name').fill(account.displayName)
  await page.getByLabel('Password', { exact: true }).fill(account.password)
  await page.getByLabel('I confirm that I am at least 18 years old.').check()
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('navigation', { name: 'Account' }).getByText(account.displayName)).toBeVisible()
}

async function openRoulette(page: Page): Promise<void> {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Roulette' }).click()
  await expect(page.getByRole('region', { name: 'Place your bets' })).toBeVisible()
}

async function chooseChip(page: Page, value: number): Promise<void> {
  await page.getByRole('radio', { name: new RegExp(`^${value} coins?`) }).focus()
  await page.keyboard.press('Space')
}

const cell = (page: Page, spoken: string) => page.getByRole('button', { name: new RegExp(`^${spoken}\\.`) }).first()
const resultSection = (page: Page) => page.getByRole('heading', { name: 'Result' }).locator('xpath=ancestor::section[1]')
const balanceBadge = (page: Page) => page.locator('header').getByText(/^Balance/).locator('xpath=..')

function recordSpins(page: Page): Request[] {
  const spins: Request[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && isSpin(request.url())) spins.push(request)
  })
  return spins
}

function roundsWithKey(key: string): number {
  return Number(queryDatabase("select count(*) from roulette_rounds where request_id = :'key'::uuid", { key })[0][0])
}

test.describe('W5 — roulette against the real backend', () => {
  test('a round with several bets: one request with a new key, the server result after the wheel, shared balance and history', async ({ page }) => {
    await registerInBrowser(page, 'w5-round')
    await expect(balanceBadge(page)).toContainText('1,000 coins')
    const spins = recordSpins(page)
    await openRoulette(page)

    await chooseChip(page, 10)
    await cell(page, '17, black').click()
    await cell(page, 'Red').click()
    await cell(page, '2nd dozen, 13 to 24').click()
    await chooseChip(page, 25)
    await cell(page, 'Odd').click()
    await page.getByRole('button', { name: 'Review bets' }).first().click()
    const review = page.getByRole('heading', { name: 'Review your bets' }).locator('xpath=ancestor::section[1]')
    await expect(review).toBeVisible()
    await expect(review).toContainText(/Total stake\s*55 coins/)

    const answer = page.waitForResponse((response) => isSpin(response.url()))
    await page.getByRole('button', { name: 'Confirm and spin' }).click()
    const response = await answer
    expect(response.status()).toBe(200)
    const result = (await response.json()) as SpinResult

    expect(spins).toHaveLength(1)
    const key = spins[0].headers()['idempotency-key']
    expect(key).toMatch(UUID_V4)
    expect(spins[0].headers()['x-xsrf-token']).toBeTruthy()
    expect(JSON.parse(spins[0].postData() ?? '')).toEqual({
      bets: [
        { category: 'STRAIGHT', number: 17, amount: 10 },
        { category: 'RED_BLACK', color: 'RED', amount: 10 },
        { category: 'DOZEN', dozen: 'SECOND', amount: 10 },
        { category: 'EVEN_ODD', parity: 'ODD', amount: 25 },
      ],
    })

    // The settlement is the server's; the test only checks it against the published rules.
    expect(result.bets).toHaveLength(4)
    for (const bet of result.bets) expect(bet.payout).toBe(payoutByRules(bet, result.winningNumber))
    expect(result.totalStake).toBe(55)
    expect(result.totalPayout).toBe(result.bets.reduce((sum, bet) => sum + bet.payout, 0))
    expect(result.balance).toBe(1000 - 55 + result.totalPayout)

    // The result is revealed after the wheel; then it shows exactly the server's values.
    const colour = result.winningColor[0] + result.winningColor.slice(1).toLowerCase()
    await expect(resultSection(page)).toContainText(`${result.winningNumber} · ${colour}`, { timeout: 15_000 })
    await expect(resultSection(page)).toContainText(balanceAfter(result.balance))
    await expect(balanceBadge(page)).toContainText(coins(result.balance))

    // Exactly one committed round for the key, with one stake entry (and one payout entry on a win).
    expect(roundsWithKey(key)).toBe(1)
    const entries = queryDatabase(
      "select t.type, t.amount from wallet_transactions t join roulette_rounds r on t.reference = r.id::text where r.request_id = :'key'::uuid order by t.id",
      { key },
    )
    expect(entries).toEqual(result.totalPayout > 0 ? [['ROULETTE_STAKE', '-55'], ['ROULETTE_PAYOUT', String(result.totalPayout)]] : [['ROULETTE_STAKE', '-55']])

    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Wallet' }).click()
    const rows = page.getByRole('table', { name: 'Wallet entries, newest first' }).getByRole('row')
    await expect(rows.nth(1)).toContainText(result.totalPayout > 0 ? 'Roulette payout' : 'Roulette stake')
    await expect(rows.nth(1)).toContainText(coins(result.balance))
    expect(spins).toHaveLength(1)
  })

  test('a lost answer: unresolved after a reload, nothing sent until "Retry spin", same key and body, the stored round', async ({ page }) => {
    await registerInBrowser(page, 'w5-lost')
    const spins = recordSpins(page)
    await openRoulette(page)
    await chooseChip(page, 10)
    await cell(page, 'Black').click()
    await page.getByRole('button', { name: 'Review bets' }).first().click()

    let lost: { key: string; body: string; result: SpinResult } | undefined
    await page.route('**/api/v1/games/roulette/spins', async (route) => {
      const upstream = await route.fetch() // the real server settles the round
      lost = { key: route.request().headers()['idempotency-key'], body: route.request().postData() ?? '', result: (await upstream.json()) as SpinResult }
      await route.abort('connectionreset') // its answer never reaches the page
    }, { times: 1 })
    await page.getByRole('button', { name: 'Confirm and spin' }).click()
    await expect(page.getByRole('heading', { name: 'Spin not confirmed' })).toBeVisible()
    expect(lost).toBeDefined()
    expect(roundsWithKey(lost!.key)).toBe(1)

    await page.reload()
    await expect(page.getByText(/An earlier spin could not be confirmed/)).toBeVisible()
    await page.waitForTimeout(2000)
    expect(spins).toHaveLength(1) // no automatic replay after the reload

    const answer = page.waitForResponse((response) => isSpin(response.url()))
    await page.getByRole('button', { name: 'Retry spin' }).click()
    const retried = await answer
    expect(spins).toHaveLength(2)
    expect(spins[1].headers()['idempotency-key']).toBe(lost!.key)
    expect(spins[1].postData()).toBe(lost!.body)
    expect(await retried.json()).toEqual(lost!.result)

    await expect(resultSection(page)).toContainText(balanceAfter(lost!.result.balance), { timeout: 15_000 })
    await expect(balanceBadge(page)).toContainText(coins(lost!.result.balance))
    expect(await page.evaluate(() => Object.keys(sessionStorage).filter((k) => k.startsWith('casino.roulette.pendingSpin')))).toEqual([])
    expect(roundsWithKey(lost!.key)).toBe(1)
    const stakes = queryDatabase(
      "select count(*) from wallet_transactions t join roulette_rounds r on t.reference = r.id::text where r.request_id = :'key'::uuid and t.type = 'ROULETTE_STAKE'",
      { key: lost!.key },
    )
    expect(stakes).toEqual([['1']])
  })
})
