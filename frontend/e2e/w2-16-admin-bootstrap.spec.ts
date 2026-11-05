/**
 * W2-16: the administrator bootstrap (AC-9.4, S9, S16) in a real Chrome against the real frontend,
 * backend and PostgreSQL. The backend is restarted with throwaway bootstrap credentials that only
 * this test process and the backend process know; the normal backend (without them) is restored
 * afterwards. `/api/v1/admin/**` has no endpoint before Week 10, so an unknown administrative path
 * shows the rule: anonymous 401, PLAYER 403, ADMIN past the rule (404).
 */
import { createHash } from 'node:crypto'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { newAccount, signInFromHome, type Account } from './support/accounts'
import { backendLog, queryDatabase, startBackend, stopBackend } from './support/stack'

const header = (page: Page) => page.getByRole('navigation', { name: 'Account' })
const accountPanel = (page: Page) => page.getByRole('region', { name: 'Your account' })
const details = (page: Page) => page.getByRole('region', { name: 'Account details' })
const roleIn = (region: Locator) => region.locator('dt:text-is("Role") + dd')
const ADMIN_PATH = '/api/v1/admin/users'
const EXISTING = 'The bootstrap administrator already exists; nothing was changed'

interface AccountRow {
  id: string
  role: string
  status: string
  displayName: string
  /** A fingerprint of the password hash, so a failing comparison does not print the hash. */
  hash: string
  bcrypt12: boolean
}

/** The rows with this email, read directly from the throwaway database. */
function accountRows(email: string): AccountRow[] {
  return queryDatabase("SELECT id, role, status, display_name, password_hash FROM users WHERE email = :'email'", { email })
    .map(([id, role, status, displayName, hash]) => ({
      id, role, status, displayName,
      hash: createHash('sha256').update(hash).digest('hex').slice(0, 12),
      bcrypt12: hash.startsWith('{bcrypt}$2a$12$'),
    }))
}

const administrators = () => Number(queryDatabase("SELECT count(*) FROM users WHERE role = 'ADMIN'")[0][0])
const occurrences = (text: string, part: string) => text.split(part).length - 1

/** A request sent by the page itself (same origin, the page's cookies); returns the status and the body's `code`/`role`. */
async function browserRequest(page: Page, path: string, init: { method?: string; body?: unknown } = {}) {
  return page.evaluate(async ({ path, method, body }) => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method && method !== 'GET') {
      await fetch('/api/v1/auth/csrf')
      headers['Content-Type'] = 'application/json'
      headers['X-XSRF-TOKEN'] = decodeURIComponent(document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1] ?? '')
    }
    const response = await fetch(path, { method: method ?? 'GET', headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const json = (await response.json().catch(() => ({}))) as { code?: string; role?: string }
    return { status: response.status, code: json.code, role: json.role }
  }, { path, method: init.method, body: init.body })
}

test.describe('Administrator bootstrap', () => {
  test('creates the administrator once; ADMIN in the browser; players and registrations never get the role; a restart changes nothing', async ({ page, browser }) => {
    test.setTimeout(300_000)
    const admin: Account = { ...newAccount('admin'), displayName: 'Administrator' }
    const bootstrap = { APP_BOOTSTRAP_ADMIN_EMAIL: admin.email, APP_BOOTSTRAP_ADMIN_PASSWORD: admin.password }

    try {
      await test.step('the backend creates the administrator at start-up', async () => {
        expect(accountRows(admin.email)).toHaveLength(0)
        await stopBackend()
        await startBackend(bootstrap)

        const rows = accountRows(admin.email)
        expect(rows).toHaveLength(1)
        expect(rows[0]).toMatchObject({ role: 'ADMIN', status: 'ACTIVE', displayName: 'Administrator', bcrypt12: true })
        expect(administrators()).toBe(1)
        // A boolean, so a failure never prints the password.
        expect(backendLog().includes(admin.password)).toBe(false)
      })

      await test.step('the administrator signs in in the browser; profile and UI show the role', async () => {
        await signInFromHome(page, admin)
        expect(await browserRequest(page, '/api/v1/users/me')).toMatchObject({ status: 200, role: 'ADMIN' })
        await expect(roleIn(accountPanel(page))).toHaveText('Administrator')
        await header(page).getByRole('link', { name: 'My profile' }).click()
        await expect(roleIn(details(page))).toHaveText('Administrator')
        await expect(details(page).getByText(admin.email)).toBeVisible()
        await expect(page.getByText('Your email, role and 18+ confirmation cannot be changed here.')).toBeVisible()
        // Past the ADMIN rule: there is no administrative endpoint yet, so the path is simply unknown.
        expect(await browserRequest(page, ADMIN_PATH)).toMatchObject({ status: 404, code: 'NOT_FOUND' })
        // CSRF still applies: with a token the request reaches the (missing) endpoint, without one it does not.
        expect(await page.evaluate(async (path) => (await (await fetch(path, { method: 'POST' })).json()).code, ADMIN_PATH))
          .toBe('CSRF_TOKEN_INVALID')
      })

      await test.step('anonymous visitors cannot use /api/v1/admin/**', async () => {
        const anonymous = await browser.newPage()
        await anonymous.goto('/')
        await anonymous.waitForLoadState('networkidle')
        expect(await browserRequest(anonymous, ADMIN_PATH)).toMatchObject({ status: 401, code: 'AUTHENTICATION_REQUIRED' })
        await anonymous.close()
      })

      await test.step('registration ignores a requested ADMIN role; the PLAYER cannot use /api/v1/admin/**', async () => {
        // Registered from the browser (not as a fixture): the fixtures already use the hourly
        // registration limit of their client IP.
        const mallory = newAccount('admin-claim')
        const player = await browser.newPage()
        await player.goto('/register')
        await player.waitForLoadState('networkidle')
        const registered = await browserRequest(player, '/api/v1/auth/register', {
          method: 'POST',
          body: { email: mallory.email, password: mallory.password, displayName: mallory.displayName, adultConfirmed: true,
            role: 'ADMIN', roles: ['ADMIN'], authorities: ['ROLE_ADMIN'] },
        })
        expect(registered).toMatchObject({ status: 201, role: 'PLAYER' })
        await player.goto('/')
        await expect(roleIn(accountPanel(player))).toHaveText('Player')

        for (const path of ['/api/v1/admin', ADMIN_PATH, '/api/v1/admin/test/probe']) {
          expect(await browserRequest(player, path), path).toMatchObject({ status: 403, code: 'ACCESS_DENIED' })
        }
        expect(await browserRequest(player, ADMIN_PATH, { method: 'POST', body: {} }))
          .toMatchObject({ status: 403, code: 'ACCESS_DENIED' })
        // The profile update ignores a role in the request.
        expect(await browserRequest(player, '/api/v1/users/me', { method: 'PATCH', body: { role: 'ADMIN' } }))
          .toMatchObject({ status: 200, role: 'PLAYER' })
        await player.waitForLoadState('networkidle')
        await player.close()
        expect(accountRows(mallory.email)[0].role).toBe('PLAYER')
        expect(administrators()).toBe(1)
      })

      await test.step('a restart with bootstrap values (another password) neither duplicates nor changes the administrator', async () => {
        // A changed display name shows whether the restart overwrites the account.
        await page.getByLabel('Display name').fill('E2E Admin renamed')
        await page.getByRole('button', { name: 'Save changes' }).click()
        await expect(page.getByText('Your profile has been saved.')).toBeVisible()
        const before = accountRows(admin.email)
        const reported = occurrences(backendLog(), EXISTING)

        await stopBackend()
        await startBackend({ ...bootstrap, APP_BOOTSTRAP_ADMIN_PASSWORD: newAccount('admin-other').password })

        expect(accountRows(admin.email)).toEqual(before)
        expect(before[0]).toMatchObject({ role: 'ADMIN', displayName: 'E2E Admin renamed' })
        expect(administrators()).toBe(1)
        expect(occurrences(backendLog(), EXISTING)).toBe(reported + 1)

        // The session survives the restart (Spring Session JDBC); the original password still works.
        await page.goto('/')
        await expect(header(page).getByText('E2E Admin renamed')).toBeVisible()
        await header(page).getByRole('button', { name: 'Sign out' }).click()
        await expect(header(page).getByRole('link', { name: 'Sign in' })).toBeVisible()
        await signInFromHome(page, { ...admin, displayName: 'E2E Admin renamed' })
        await expect(roleIn(accountPanel(page))).toHaveText('Administrator')
      })
    } finally {
      // The normal backend, without bootstrap values, for the other tests.
      await stopBackend()
      await startBackend()
    }
  })
})
