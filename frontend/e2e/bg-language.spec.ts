/**
 * The Bulgarian interface against the real backend and PostgreSQL of the isolated W2-16 stack: register
 * and sign in in Bulgarian, play one round, read the wallet, and switch the language while bets are
 * pending and after the round. Nothing is mocked and no number is forced. Switching the language must
 * never send a spin again or change the settlement.
 */
import { expect, test, type Page, type Request } from '@playwright/test'
import { newAccount } from './support/accounts'
import { queryDatabase } from './support/stack'

interface SpinResult {
  roundId: string
  winningNumber: number
  winningColor: 'RED' | 'BLACK' | 'GREEN'
  bets: { amount: number; payout: number }[]
  totalStake: number
  totalPayout: number
  balance: number
}

const isSpin = (url: string) => url.endsWith('/api/v1/games/roulette/spins')
const coinsBg = (n: number) => `${new Intl.NumberFormat('bg-BG').format(n)} ${n === 1 ? 'монета' : 'монети'}`
const coinsEn = (n: number) => `${new Intl.NumberFormat('en-GB').format(n)} ${n === 1 ? 'coin' : 'coins'}`
const COLOUR_BG = { RED: 'Червено', BLACK: 'Черно', GREEN: 'Зелено' } as const
const COLOUR_EN = { RED: 'Red', BLACK: 'Black', GREEN: 'Green' } as const

const switchTo = (page: Page, name: 'EN English' | 'BG Български') => page.getByRole('button', { name, exact: true }).click()
const cell = (page: Page, spoken: string) => page.getByRole('button', { name: new RegExp(`^${spoken}\\.`) }).first()
const resultSection = (page: Page) =>
  page.getByRole('heading', { name: /^(Резултат|Result)$/ }).locator('xpath=ancestor::section[1]')
const balanceBadge = (page: Page) => page.locator('header').getByText(/^(Баланс|Balance)$/).locator('xpath=..')

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

test('Bulgarian: register and sign in, play a round, read the wallet; switching the language never resends a spin', async ({ page }) => {
  const account = newAccount('bg')

  // Registration in Bulgarian (through the real form; the Vite proxy forwards to the backend).
  await page.goto('/register')
  await switchTo(page, 'BG Български')
  await expect(page.locator('html')).toHaveAttribute('lang', 'bg')
  await expect(page).toHaveTitle('Online Casino Simulator')
  await expect(page.getByRole('heading', { level: 1, name: 'Създайте своя профил' })).toBeVisible()
  await page.getByLabel('Имейл').fill(account.email)
  await page.getByLabel('Показвано име').fill(account.displayName)
  await page.getByLabel('Парола', { exact: true }).fill(account.password)
  await page.getByLabel('Потвърждавам, че съм навършил(а) 18 години.').check()
  await page.getByRole('button', { name: 'Регистрация' }).click()
  const accountNav = page.getByRole('navigation', { name: 'Профил' })
  await expect(accountNav.getByText(account.displayName)).toBeVisible()

  // Sign out and sign in again, still in Bulgarian (the choice survives the change of session).
  await accountNav.getByRole('button', { name: 'Изход' }).click()
  await accountNav.getByRole('link', { name: 'Вход' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Вход' })).toBeVisible()
  await page.getByLabel('Имейл').fill(account.email)
  await page.getByLabel('Парола', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: 'Вход', exact: true }).click()
  await expect(accountNav.getByText(account.displayName)).toBeVisible()
  await expect(balanceBadge(page)).toContainText(coinsBg(1000))

  const spins = recordSpins(page)
  await page.getByRole('navigation', { name: 'Основна навигация' }).getByRole('link', { name: 'Рулетка' }).click()
  await expect(page.getByRole('region', { name: 'Направете залозите си' })).toBeVisible()
  await cell(page, '17, черно').click()
  await cell(page, 'Червено').click()
  await cell(page, 'Червено').click()

  // A change of language while bets are pending: the slip is kept, nothing is sent.
  await switchTo(page, 'EN English')
  const slipEn = page.getByRole('region', { name: 'Bet slip' })
  await expect(slipEn.getByRole('button', { name: 'Remove Number 17, 10 coins' })).toBeVisible()
  await expect(slipEn.getByRole('button', { name: 'Remove Red, 20 coins' })).toBeVisible()
  await switchTo(page, 'BG Български')
  const slip = page.getByRole('region', { name: 'Фиш със залози' })
  await expect(slip.getByRole('button', { name: 'Премахни Число 17, 10 монети' })).toBeVisible()
  await expect(slip.getByRole('button', { name: 'Премахни Червено, 20 монети' })).toBeVisible()
  expect(spins).toHaveLength(0)

  await page.getByRole('button', { name: 'Преглед на залозите' }).first().click()
  await expect(page.getByRole('heading', { name: 'Прегледайте залозите си' })).toBeVisible()
  const answer = page.waitForResponse((response) => isSpin(response.url()))
  await page.getByRole('button', { name: 'Потвърди и завърти' }).click()
  const response = await answer
  expect(response.status()).toBe(200)
  const result = (await response.json()) as SpinResult

  // Exactly the contract body, once, whatever the language.
  expect(spins).toHaveLength(1)
  const key = spins[0].headers()['idempotency-key']
  expect(JSON.parse(spins[0].postData() ?? '')).toEqual({
    bets: [
      { category: 'STRAIGHT', number: 17, amount: 10 },
      { category: 'RED_BLACK', color: 'RED', amount: 20 },
    ],
  })
  expect(result.totalStake).toBe(30)
  expect(result.balance).toBe(1000 - 30 + result.totalPayout)

  // The server's result, in Bulgarian.
  const section = resultSection(page)
  await expect(section).toContainText(`${result.winningNumber} · ${COLOUR_BG[result.winningColor]}`, { timeout: 15_000 })
  await expect(section).toContainText(`Заложено: ${coinsBg(30)}, върнато: ${coinsBg(result.totalPayout)}`)
  await expect(section).toContainText(new RegExp(`Баланс след този рунд\\s*${coinsBg(result.balance)}`))
  await expect(balanceBadge(page)).toContainText(coinsBg(result.balance))

  // A change of language after the round: the same result in English, nothing sent, nothing settled again.
  await switchTo(page, 'EN English')
  await expect(section).toContainText(`${result.winningNumber} · ${COLOUR_EN[result.winningColor]}`)
  await expect(section).toContainText(new RegExp(`Balance after this round\\s*${coinsEn(result.balance)}`))
  await expect(balanceBadge(page)).toContainText(coinsEn(result.balance))
  await switchTo(page, 'BG Български')
  await expect(section).toContainText(new RegExp(`Баланс след този рунд\\s*${coinsBg(result.balance)}`))
  await page.waitForTimeout(1500)
  expect(spins).toHaveLength(1)
  expect(roundsWithKey(key)).toBe(1)
  const entries = queryDatabase(
    "select t.type, t.amount from wallet_transactions t join roulette_rounds r on t.reference = r.id::text where r.request_id = :'key'::uuid order by t.id",
    { key },
  )
  expect(entries).toEqual(
    result.totalPayout > 0 ? [['ROULETTE_STAKE', '-30'], ['ROULETTE_PAYOUT', String(result.totalPayout)]] : [['ROULETTE_STAKE', '-30']],
  )

  // The wallet and its history in Bulgarian, as the server recorded them.
  await page.getByRole('navigation', { name: 'Основна навигация' }).getByRole('link', { name: 'Портфейл' }).click()
  const balanceCard = page.getByRole('region', { name: 'Вашият баланс' })
  await expect(balanceCard).toContainText(coinsBg(result.balance))
  const rows = page.getByRole('table', { name: 'Записи в портфейла, най-новите първи' }).getByRole('row')
  const entryCount = result.totalPayout > 0 ? 3 : 2
  await expect(rows).toHaveCount(entryCount + 1)
  await expect(rows.nth(1)).toContainText(result.totalPayout > 0 ? 'Изплащане от рулетка' : 'Залог в рулетка')
  await expect(rows.nth(1)).toContainText(coinsBg(result.balance))
  await expect(rows.nth(entryCount)).toContainText('Начален баланс')
  await expect(rows.nth(entryCount)).toContainText(`+${coinsBg(1000)}`)
  await expect(page.getByText(`Показване на ${entryCount} записа: цялата ви история.`)).toBeVisible()
  expect(spins).toHaveLength(1)
})
