import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { pendingSpinStorageKey, type PendingSpin } from '../features/roulette/pendingSpinStore'
import { renderApp } from '../test/renderApp'
import { alice, spinRequest, spinResult, startingWallet, upStatus } from '../test/fixtures'
import { csrfEndpoint, jsonResponse, problemResponse, stubApi } from '../test/http'
import { LOCALE_STORAGE_KEY } from './locales'

const SPIN = 'POST /api/v1/games/roulette/spins'
const WALLET = 'GET /api/v1/wallet'
const ME = 'GET /api/v1/users/me'

type Route = Parameters<typeof stubApi>[0][string]

function backend({ user = true, spin = (() => jsonResponse(spinResult)) as Route } = {}) {
  return stubApi({
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    [ME]: () => (user ? jsonResponse(alice) : problemResponse(401, 'AUTHENTICATION_REQUIRED')),
    [WALLET]: [() => jsonResponse(startingWallet), () => jsonResponse({ balance: 1330, updatedAt: '2026-11-20T10:15:31Z' })],
    [SPIN]: spin,
  })
}

const language = () => screen.getByRole('group', { name: /^(Language|Език)$/ })
const english = () => within(language()).getByRole('button', { name: 'EN English' })
const bulgarian = () => within(language()).getByRole('button', { name: 'BG Български' })
const html = () => document.documentElement

describe('switching the interface language', () => {
  it('switches EN → BG → EN for a visitor: texts, <html lang>, title, description and the pressed button', async () => {
    // index.html has this meta element; the test document does not.
    const description = Object.assign(document.createElement('meta'), { name: 'description', content: '' })
    document.head.append(description)
    const api = backend({ user: false })
    renderApp('/')
    const user = userEvent.setup()

    expect(await screen.findByRole('heading', { level: 1, name: 'Learn the odds. Play without risk.' })).toBeInTheDocument()
    expect(html()).toHaveAttribute('lang', 'en')
    expect(english()).toHaveAttribute('aria-pressed', 'true')
    expect(bulgarian()).toHaveAttribute('aria-pressed', 'false')
    expect(bulgarian()).toHaveAttribute('lang', 'bg')
    expect(await within(screen.getByRole('navigation', { name: 'Account' })).findByRole('link', { name: 'Create account' })).toBeInTheDocument()

    await user.click(bulgarian())

    expect(screen.getByRole('heading', { level: 1, name: 'Научете шансовете. Играйте без риск.' })).toBeInTheDocument()
    expect(html()).toHaveAttribute('lang', 'bg')
    // The product name stays English; the description around it is translated.
    expect(document.title).toBe('Online Casino Simulator')
    expect(screen.getByRole('link', { name: 'Online Casino Simulator' })).toHaveAttribute('href', '/')
    expect(description.content).toBe(
      'Online Casino Simulator — симулатор с автентични правила и вероятности на казино игрите, само с виртуална валута.',
    )
    expect(bulgarian()).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('group', { name: 'Език' })).toBeInTheDocument()
    expect(within(screen.getByRole('navigation', { name: 'Профил' })).getByRole('link', { name: 'Регистрация' })).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toHaveTextContent(/виртуални монети без парична стойност/)
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('bg')

    await user.click(english())

    expect(screen.getByRole('heading', { level: 1, name: 'Learn the odds. Play without risk.' })).toBeInTheDocument()
    expect(html()).toHaveAttribute('lang', 'en')
    expect(document.title).toBe('Online Casino Simulator')
    expect(description.content).toMatch(/^Online Casino Simulator — authentic casino rules/)
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
    description.remove()
    // Only the language changed: the session was asked for once, nothing was sent again.
    expect(api.requestsTo(ME)).toHaveLength(1)
  })

  it('is kept after a reload (the saved choice is read at start-up)', async () => {
    backend({ user: false })
    const first = renderApp('/')
    await userEvent.setup().click(bulgarian())
    first.unmount()

    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Научете шансовете. Играйте без риск.' })).toBeInTheDocument()
    expect(html()).toHaveAttribute('lang', 'bg')
    expect(bulgarian()).toHaveAttribute('aria-pressed', 'true')
  })

  it('starts in English when the saved value is not a known language', async () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr')
    backend({ user: false })
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Learn the odds. Play without risk.' })).toBeInTheDocument()
    expect(html()).toHaveAttribute('lang', 'en')
    expect(english()).toHaveAttribute('aria-pressed', 'true')
  })

  it('localises the profile: dates, role and the server’s interest catalogue', async () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'bg')
    stubApi({
      'GET /api/v1/system/status': () => jsonResponse(upStatus),
      [ME]: () => jsonResponse({ ...alice, interests: ['PROBABILITY'] }),
      [WALLET]: () => jsonResponse(startingWallet),
      'GET /api/v1/interests': () =>
        jsonResponse([{ code: 'PROBABILITY', name: 'Probability & odds', description: 'How likely outcomes are and how odds are expressed' }]),
    })
    renderApp('/profile')

    const details = await screen.findByRole('region', { name: 'Данни за профила' })
    expect(within(details).getByText('Играч')).toBeInTheDocument()
    expect(within(details).getAllByText('2 ноември 2026 г.')).toHaveLength(2)
    expect(await within(details).findByText('Вероятности и коефициенти')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Вероятности и коефициенти/ })).toBeChecked()
  })
})

describe('switching the language on the roulette page', () => {
  const table = () => screen.getByRole('region', { name: /^(Place your bets|Направете залозите си)$/ })
  const slip = () => screen.getByRole('region', { name: /^(Bet slip|Фиш със залози)$/ })
  const cell = (name: RegExp) => within(table()).getByRole('button', { name })
  const stored = () => {
    const raw = sessionStorage.getItem(pendingSpinStorageKey(alice.id))
    return raw === null ? null : (JSON.parse(raw) as PendingSpin)
  }

  async function openWithExampleBets() {
    renderApp('/roulette')
    const user = userEvent.setup()
    await screen.findByRole('region', { name: 'Place your bets' })
    await within(screen.getByRole('region', { name: 'Your balance' })).findByText('1,000 coins')
    await user.click(cell(/^17, black/))
    await user.click(cell(/^Red\./))
    await user.click(cell(/^Red\./))
    return user
  }

  it('keeps the bets, the balance and the session; translates bet names and the last announcement', async () => {
    const api = backend()
    const user = await openWithExampleBets()
    const sent = api.requests.length

    await user.click(bulgarian())

    const rows = within(within(slip()).getByRole('list', { name: 'Вашите залози' })).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      'Число 17Залог на число · плаща 35 към 110 монети✕',
      'ЧервеноЗалог на червено или черно · плаща 1 към 120 монети✕',
    ])
    expect(within(slip()).getByRole('button', { name: 'Премахни Червено, 20 монети' })).toBeInTheDocument()
    expect(within(slip()).getByRole('status')).toHaveTextContent('Червено: 20 монети. 2 залога, общо 30 монети.')
    expect(within(slip()).getByText('30 монети')).toBeInTheDocument()
    expect(cell(/^17, черно\. Залог на число, плаща 35 към 1\. Заложено: 10 монети\.$/)).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Вашият баланс' })).getByText('1000 монети')).toBeInTheDocument()

    await user.click(english())

    expect(within(slip()).getByRole('status')).toHaveTextContent('Red: 20 coins. 2 bets, total 30 coins.')
    expect(within(slip()).getByRole('button', { name: 'Remove Number 17, 10 coins' })).toBeInTheDocument()
    // Nothing was sent or read again: no session check, no wallet read, no spin.
    expect(api.requests).toHaveLength(sent)
    expect(api.requestsTo(WALLET)).toHaveLength(1)
  })

  it('plays a round in Bulgarian: the request is unchanged and the server’s result is shown in Bulgarian', async () => {
    const api = backend()
    const user = await openWithExampleBets()
    await user.click(bulgarian())

    await user.click(within(slip()).getByRole('button', { name: 'Преглед на залозите' }))
    expect(within(slip()).getByRole('heading', { name: 'Прегледайте залозите си' })).toHaveFocus()
    await user.click(within(slip()).getByRole('button', { name: 'Потвърди и завърти' }))

    const result = await screen.findByRole('region', { name: 'Резултат' })
    expect(within(result).getByRole('status')).toHaveTextContent('17 · ЧерноЗаложено: 30 монети, върнато: 360 монети, нетно: +330 монети.')
    const bets = within(within(result).getByRole('list', { name: 'Вашите залози в този рунд' })).getAllByRole('listitem')
    expect(bets[0]).toHaveTextContent('Число 17Залог 10 монетиПечелиИзплащане 360 монети')
    expect(bets[1]).toHaveTextContent('ЧервеноЗалог 20 монетиГубиИзплащане 0 монети')
    expect(within(result).getByText('Баланс след този рунд')).toBeInTheDocument()
    // The language changes texts only: exactly the contract body was sent, once.
    expect(api.requestsTo(SPIN)).toHaveLength(1)
    expect(api.requestsTo(SPIN)[0]?.body).toEqual(spinRequest)
  })

  it('shows a definitive spin error and the server’s field messages in the language of the moment', async () => {
    const api = backend({
      spin: () =>
        problemResponse(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'bets[0].amount', message: 'must be less than or equal to 1000' }],
        }),
    })
    const user = await openWithExampleBets()
    await user.click(bulgarian())
    await user.click(within(slip()).getByRole('button', { name: 'Преглед на залозите' }))
    await user.click(within(slip()).getByRole('button', { name: 'Потвърди и завърти' }))

    const alert = await within(slip()).findByRole('alert')
    expect(alert).toHaveTextContent('Някои залози не бяха приети. Променете ги и ги прегледайте отново.')
    expect(within(slip()).getByText('трябва да е по-малко или равно на 1000')).toBeInTheDocument()

    await user.click(english())

    expect(within(slip()).getByRole('alert')).toHaveTextContent('Some bets were not accepted. Change them and review again.')
    expect(within(slip()).getByText('must be less than or equal to 1000')).toBeInTheDocument()
    expect(api.requestsTo(SPIN)).toHaveLength(1)
  })

  it('keeps an unresolved spin and its key across a change of language; the retry repeats key and body', async () => {
    const api = backend({ spin: [() => Promise.reject(new TypeError('Failed to fetch')), () => jsonResponse(spinResult)] })
    const user = await openWithExampleBets()
    await user.click(within(slip()).getByRole('button', { name: 'Review bets' }))
    await user.click(within(slip()).getByRole('button', { name: 'Confirm and spin' }))

    expect(await within(slip()).findByRole('heading', { name: 'Spin not confirmed' })).toBeInTheDocument()
    const record = stored()
    const key = api.requestsTo(SPIN)[0]?.headers['Idempotency-Key']
    expect(record).toMatchObject({ state: 'unresolved', ambiguous: true, idempotencyKey: key })

    await user.click(bulgarian())

    expect(within(slip()).getByRole('heading', { name: 'Завъртането не е потвърдено' })).toBeInTheDocument()
    expect(within(slip()).getByRole('alert')).toHaveTextContent(/^Не успяхме да потвърдим това завъртане/)
    expect(cell(/^Червено\./)).toHaveAttribute('aria-disabled', 'true')
    // The record is untouched and nothing was sent by the change of language.
    expect(stored()).toEqual(record)
    expect(api.requestsTo(SPIN)).toHaveLength(1)

    await user.click(within(slip()).getByRole('button', { name: 'Повтори завъртането' }))

    expect(await screen.findByRole('region', { name: 'Резултат' })).toBeInTheDocument()
    const [first, retry] = api.requestsTo(SPIN)
    expect(retry?.headers['Idempotency-Key']).toBe(key)
    expect(retry?.body).toEqual(first?.body)
    await waitFor(() => expect(stored()).toBeNull())
  })
})
