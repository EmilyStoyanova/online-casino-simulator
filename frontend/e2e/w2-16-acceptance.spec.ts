/**
 * W2-16 browser acceptance scenarios (docs/plans/week-02.md, "W2-16 verification record"), in a real
 * Chrome against the real frontend, backend and PostgreSQL. Nothing is mocked; the tests only
 * observe the responses (scenario 4 holds two requests so that they overlap, unchanged).
 */
import { createHash } from 'node:crypto'
import { expect, test, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { createAccount, newAccount, recordProfileUpdates, signIn, signInFromHome } from './support/accounts'
import { FRONTEND_URL, startBackend, stopBackend } from './support/stack'

const header = (page: Page) => page.getByRole('navigation', { name: 'Account' })
const details = (page: Page) => page.getByRole('region', { name: 'Account details' })
const nameInput = (page: Page) => page.getByLabel('Display name')
// The accessible name is the interest's name followed by its description.
const interest = (page: Page, name: string) => page.getByRole('checkbox', { name: new RegExp(`^${name}`) })
const save = (page: Page) => page.getByRole('button', { name: 'Save changes' })
const saved = (page: Page) => page.getByText('Your profile has been saved.')

async function openProfile(page: Page): Promise<void> {
  await header(page).getByRole('link', { name: 'My profile' }).click()
  await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible()
  await expect(page.getByRole('checkbox')).toHaveCount(8)
}

/** A short fingerprint of the session cookie, to compare sessions without exposing them. */
async function sessionFingerprint(context: BrowserContext): Promise<string | undefined> {
  const cookie = (await context.cookies()).find((c) => c.name === 'SESSION')
  return cookie && createHash('sha256').update(cookie.value).digest('hex').slice(0, 12)
}

async function tabTo(page: Page, target: Locator, maxPresses = 40): Promise<void> {
  for (let i = 0; i < maxPresses; i++) {
    if (await target.evaluate((element) => element === document.activeElement)) return
    await page.keyboard.press('Tab')
  }
  throw new Error('Element not reachable with Tab')
}

test.describe('Scenario 1 — protected /profile and safe return', () => {
  test('anonymous visit → login page with explanation → back on /profile after sign-in', async ({ page }) => {
    const account = await createAccount('s1')

    await page.goto('/profile')

    await expect(page).toHaveURL(/\/login\?returnTo=%2Fprofile$/)
    await expect(page.getByText('Please sign in to open this page.')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'My profile' })).toHaveCount(0)
    await signIn(page, account)
    await expect(page).toHaveURL(/\/profile$/)
    await expect(details(page).getByText(account.email)).toBeVisible()
  })

  test('a foreign returnTo is ignored', async ({ page }) => {
    const account = await createAccount('s1b')
    await page.goto(`/login?returnTo=${encodeURIComponent('//evil.example/profile')}`)
    await signIn(page, account)
    await expect(page).toHaveURL(`${FRONTEND_URL}/`)
  })
})

test.describe('Scenario 2 — display name and shared state', () => {
  test('a new display name appears in the details, the header and the account panel at once', async ({ page }) => {
    const account = await createAccount('s2')
    await signInFromHome(page, account)
    await openProfile(page)
    const updates = recordProfileUpdates(page)

    await nameInput(page).fill('E2E Renamed')
    await save(page).click()

    await expect(saved(page)).toBeVisible()
    expect(updates).toEqual([200])
    await expect(details(page).getByText('E2E Renamed')).toBeVisible()
    await expect(header(page).getByText('E2E Renamed')).toBeVisible()
    await page.getByRole('link', { name: 'Online Casino Simulator' }).click()
    await expect(page.getByRole('region', { name: 'Your account' }).getByText('E2E Renamed')).toBeVisible()
    await page.reload()
    await expect(header(page).getByText('E2E Renamed')).toBeVisible()
  })
})

test.describe('Scenario 3 — interests persist', () => {
  test('selected interests are saved and still selected after a reload', async ({ page }) => {
    const account = await createAccount('s3')
    await signInFromHome(page, account)
    await openProfile(page)

    for (const name of ['Probability & odds', 'Statistics & data', 'History of games']) {
      await interest(page, name).check()
    }
    await expect(page.getByText(/3 of 8 selected\./)).toBeVisible()
    await save(page).click()
    await expect(saved(page)).toBeVisible()

    await page.reload()
    await expect(page.getByRole('checkbox')).toHaveCount(8)
    for (const name of ['Probability & odds', 'Statistics & data', 'History of games']) {
      await expect(interest(page, name)).toBeChecked()
    }
    await expect(interest(page, 'Mathematics of games')).not.toBeChecked()
    await expect(details(page).getByRole('list', { name: 'Saved interests' }).getByRole('listitem')).toHaveText([
      'Probability & odds',
      'Statistics & data',
      'History of games',
    ])
  })
})

test.describe('Scenario 4 — two tabs', () => {
  test('sequential saves from two tabs: each sends only its changes, the last write wins, no 409', async ({ page, context }) => {
    const account = await createAccount('s4')
    await signInFromHome(page, account)
    await openProfile(page)
    const tabB = await context.newPage()
    await tabB.goto('/profile')
    await expect(tabB.getByRole('checkbox')).toHaveCount(8)
    const updatesA = recordProfileUpdates(page)
    const updatesB = recordProfileUpdates(tabB)

    // Tab A changes the name; tab B, still showing the old name, changes the interests only.
    await nameInput(page).fill('Name from tab A')
    await save(page).click()
    await expect(saved(page)).toBeVisible()
    await interest(tabB, 'History of games').check()
    await save(tabB).click()
    await expect(saved(tabB)).toBeVisible()
    // Tab B's request did not undo tab A's name: it sent only the interests.
    await expect(details(tabB).getByText('Name from tab A')).toBeVisible()
    await page.reload()
    await expect(nameInput(page)).toHaveValue('Name from tab A')
    await expect(interest(page, 'History of games')).toBeChecked()

    // Both change the same field one after the other: documented "last one wins" (contract, W2-10).
    await nameInput(page).fill('Last from A')
    await save(page).click()
    await expect(saved(page)).toBeVisible()
    await nameInput(tabB).fill('Last from B')
    await save(tabB).click()
    await expect(saved(tabB)).toBeVisible()
    await page.reload()
    await expect(nameInput(page)).toHaveValue('Last from B')

    expect([...updatesA, ...updatesB]).toEqual([200, 200, 200, 200])
  })

  test('simultaneous saves from two tabs: an overlap is reported as a conflict without losing the edits', async ({ page, context }) => {
    const account = await createAccount('s4b')
    await signInFromHome(page, account)
    await openProfile(page)
    const tabB = await context.newPage()
    await tabB.goto('/profile')
    await expect(tabB.getByRole('checkbox')).toHaveCount(8)
    const updatesA = recordProfileUpdates(page)
    const updatesB = recordProfileUpdates(tabB)

    // Two clicks from Playwright arrive tens of milliseconds apart, after the server has finished the
    // first update. To make them genuinely overlap, both PATCH requests are held in the browser and
    // released together. They reach the real backend unchanged; the responses are not touched.
    const held: { release: () => Promise<void> }[] = []
    for (const tab of [page, tabB]) {
      await tab.route('**/api/v1/users/me', async (route) => {
        if (route.request().method() !== 'PATCH') return route.continue()
        await new Promise<void>((resolve) => {
          held.push({ release: async () => { await route.continue(); resolve() } })
          if (held.length === 2) void Promise.all(held.splice(0).map((request) => request.release()))
        })
      })
    }

    let conflictTab: Page | undefined
    let conflictValue = ''
    for (let round = 1; round <= 5 && !conflictTab; round++) {
      await nameInput(page).fill(`A round ${round}`)
      await nameInput(tabB).fill(`B round ${round}`)
      const before = updatesA.length + updatesB.length
      await Promise.all([save(page).click(), save(tabB).click()])
      await expect.poll(() => updatesA.length + updatesB.length).toBe(before + 2)
      // Only 200 or 409, never anything else.
      expect([...updatesA, ...updatesB].every((status) => status === 200 || status === 409)).toBe(true)
      if (updatesA.at(-1) === 409) [conflictTab, conflictValue] = [page, `A round ${round}`]
      else if (updatesB.at(-1) === 409) [conflictTab, conflictValue] = [tabB, `B round ${round}`]
    }

    test.info().annotations.push({
      type: 'statuses',
      description: `tab A ${updatesA.join(',')} | tab B ${updatesB.join(',')}`,
    })
    test.skip(!conflictTab, 'No overlapping request in 5 rounds; covered by ProfileIT and the W2-16 HTTP run')
    if (!conflictTab) return
    await page.unroute('**/api/v1/users/me')
    await tabB.unroute('**/api/v1/users/me')

    await expect(conflictTab.getByText(/Your profile was changed somewhere else/)).toBeVisible()
    await expect(nameInput(conflictTab)).toHaveValue(conflictValue)
    await conflictTab.getByRole('button', { name: 'Load the latest saved version' }).click()
    await expect(conflictTab.getByText(/The latest saved version is now shown/)).toBeVisible()
    // The edit is still in the form and can now be saved deliberately.
    await expect(nameInput(conflictTab)).toHaveValue(conflictValue)
    await save(conflictTab).click()
    await expect(saved(conflictTab)).toBeVisible()
  })
})

test.describe('Scenario 5 — backend unavailable', () => {
  test('a failed save keeps the edits, explains the problem and succeeds with "Try again"', async ({ page }) => {
    test.setTimeout(240_000)
    const account = await createAccount('s5')
    await signInFromHome(page, account)
    await openProfile(page)
    await nameInput(page).fill('Offline edit')
    await interest(page, 'Game theory & strategy').check()

    await stopBackend()
    try {
      await save(page).click()
      const alert = page.getByRole('alert').filter({ hasText: 'still in the form' })
      await expect(alert).toBeVisible()
      test.info().annotations.push({ type: 'message while the backend was down', description: (await alert.innerText()).trim() })
      await expect(nameInput(page)).toHaveValue('Offline edit')
      await expect(interest(page, 'Game theory & strategy')).toBeChecked()
      await expect(header(page).getByText('E2E s5')).toBeVisible()
    } finally {
      await startBackend()
    }

    await page.getByRole('alert').getByRole('button', { name: 'Try again' }).click()
    await expect(saved(page)).toBeVisible()
    await expect(header(page).getByText('Offline edit')).toBeVisible()
    await page.reload()
    await expect(interest(page, 'Game theory & strategy')).toBeChecked()
  })
})

test.describe('Scenario 6 — 375 px and keyboard', () => {
  test.use({ viewport: { width: 375, height: 812 } })

  test('no horizontal overflow at 375 px on every page, also with a long email address', async ({ page }) => {
    // A long email is shown unwrapped (truncated) on the home page and the profile: it must not widen the page.
    const account = await createAccount('s6a-responsive-long-email-addr') // ≈ 63 characters, local part < 64
    const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    for (const path of ['/', '/login', '/register']) {
      await page.goto(path)
      await expect(page.getByRole('main')).toBeVisible()
      expect(await overflow(), path).toBeLessThanOrEqual(0)
    }
    await signInFromHome(page, account)
    await expect(page.getByRole('region', { name: 'Your account' }).getByText(account.email)).toBeVisible()
    expect(await overflow(), '/ signed in').toBeLessThanOrEqual(0)
    await openProfile(page)
    expect(await overflow(), '/profile').toBeLessThanOrEqual(0)
  })

  test('sign in and edit the profile with the keyboard only; every control has a label', async ({ page }) => {
    const account = await createAccount('s6b')
    await page.goto('/login?returnTo=%2Fprofile')

    await tabTo(page, page.getByLabel('Email'))
    await page.keyboard.type(account.email)
    await tabTo(page, page.getByLabel('Password', { exact: true }))
    await page.keyboard.type(account.password)
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible()
    await expect(page.getByRole('checkbox')).toHaveCount(8)

    // Every input has a label or an accessible name.
    const unlabelled = await page.evaluate(() =>
      [...document.querySelectorAll('input, button, a')].filter((element) => {
        const input = element as HTMLInputElement
        const name = (input.labels?.length ? 'label' : '') || element.getAttribute('aria-label') || element.textContent?.trim()
        return !name
      }).length,
    )
    expect(unlabelled).toBe(0)

    await tabTo(page, interest(page, 'Psychology of decisions'))
    await page.keyboard.press('Space')
    await expect(interest(page, 'Psychology of decisions')).toBeChecked()
    await tabTo(page, save(page))
    await page.keyboard.press('Enter')
    await expect(saved(page)).toBeVisible()
  })
})

test.describe('Scenario 7 — session expiry', () => {
  test('after the real idle timeout, saving leads to the login page with an explanation and back to /profile', async ({ page }) => {
    test.setTimeout(300_000)
    await stopBackend()
    await startBackend({ APP_SESSION_TIMEOUT: '1m' })
    try {
      const account = await createAccount('s7')
      await signInFromHome(page, account)
      await openProfile(page)
      await nameInput(page).fill('Edit after expiry')

      // The session must really expire on the server: no request for longer than the idle timeout.
      await page.waitForTimeout(70_000)
      await save(page).click()

      await expect(page).toHaveURL(/\/login\?returnTo=%2Fprofile$/)
      await expect(page.getByText('Your session has ended. Sign in again to continue.')).toBeVisible()
      await expect(header(page).getByRole('link', { name: 'Sign in' })).toBeVisible()
      await signIn(page, account)
      await expect(page).toHaveURL(/\/profile$/)
      await expect(page.getByRole('heading', { name: 'My profile' })).toBeVisible()
    } finally {
      await stopBackend()
      await startBackend()
    }
  })
})

test.describe('Scenario 8 — repeated authentication', () => {
  test('register → logout → login → logout → login, with a new session each time', async ({ page, context }) => {
    const account = newAccount('s8')
    const me = async () => (await page.request.get('/api/v1/users/me')).status()
    const signedInAs = async () => {
      await expect(header(page).getByText(account.displayName)).toBeVisible()
      await expect(header(page).getByRole('link', { name: 'My profile' })).toBeVisible()
      expect(await me()).toBe(200)
    }
    const signOut = async () => {
      await header(page).getByRole('button', { name: 'Sign out' }).click()
      await expect(page.getByRole('region', { name: 'Your account' }).getByText(/You are signed out\./)).toBeVisible()
      await expect(header(page).getByRole('link', { name: 'Sign in' })).toBeVisible()
      expect(await sessionFingerprint(context)).toBeUndefined()
      expect(await me()).toBe(401)
    }

    await page.goto('/register')
    await page.getByLabel('Email').fill(account.email)
    await page.getByLabel('Display name').fill(account.displayName)
    await page.getByLabel('Password', { exact: true }).fill(account.password)
    await page.getByLabel(/at least 18 years old/).check()
    await page.getByRole('button', { name: 'Create account' }).click()
    await signedInAs()
    const sessions = [await sessionFingerprint(context)]

    await signOut()
    await header(page).getByRole('link', { name: 'Sign in' }).click()
    await signIn(page, account)
    await signedInAs()
    sessions.push(await sessionFingerprint(context))

    await signOut()
    await header(page).getByRole('link', { name: 'Sign in' }).click()
    await signIn(page, account)
    await signedInAs()
    sessions.push(await sessionFingerprint(context))

    expect(new Set(sessions).size).toBe(3)
  })
})

test.describe('Scenario 9 — registration validation', () => {
  test('field errors next to the fields, focus on the first invalid one, the common password from the server', async ({ page }) => {
    const account = newAccount('s9')
    const registrations: number[] = []
    page.on('response', (r) => {
      if (r.request().method() === 'POST' && r.url().endsWith('/api/v1/auth/register')) registrations.push(r.status())
    })
    await page.goto('/register')
    await page.getByLabel('Email').fill(account.email)
    await page.getByLabel('Display name').fill('Al')
    await page.getByLabel('Password', { exact: true }).fill('Password1234')
    await page.getByRole('button', { name: 'Create account' }).click()

    // Checked in the browser first: nothing is sent.
    await expect(page.getByLabel('Display name')).toHaveAccessibleDescription(/Use 3 to 30 characters\./)
    await expect(page.getByLabel(/at least 18 years old/)).toHaveAccessibleDescription(/Confirm that you are at least 18 years old\./)
    await expect(page.getByLabel('Display name')).toBeFocused()
    expect(registrations).toEqual([])

    // The denylist is checked only by the server.
    await page.getByLabel('Display name').fill(account.displayName)
    await page.getByLabel(/at least 18 years old/).check()
    await page.getByRole('button', { name: 'Create account' }).click()
    await expect(page.getByRole('alert')).toHaveText('Please correct the highlighted fields.')
    await expect(page.getByLabel('Password', { exact: true })).toHaveAccessibleDescription(/Must not be a commonly used password\./)
    await expect(page.getByLabel('Password', { exact: true })).toBeFocused()
    expect(registrations).toEqual([400])

    await page.getByLabel('Password', { exact: true }).fill(account.password)
    await page.getByRole('button', { name: 'Create account' }).click()
    await expect(header(page).getByText(account.displayName)).toBeVisible()
    expect(registrations).toEqual([400, 201])
  })
})
