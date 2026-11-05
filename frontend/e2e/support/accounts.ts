import { randomBytes } from 'node:crypto'
import { expect, request, type Page } from '@playwright/test'
import { BACKEND_IPV6 } from './stack'

export interface Account {
  email: string
  password: string
  displayName: string
}

/** A unique, disposable account (random password, never printed). */
export function newAccount(label: string): Account {
  const id = `${Date.now().toString(36)}${randomBytes(3).toString('hex')}`
  return {
    email: `e2e-${label}-${id}@example.test`,
    password: `E2e-${randomBytes(12).toString('base64url')}-pw`,
    displayName: `E2E ${label}`.slice(0, 30),
  }
}

/**
 * Registers an account through the real API, over IPv6. The browser reaches the backend over IPv4
 * (Vite proxy), so fixture accounts do not use up the registration limit of the browser tests
 * (10 per client IP and hour).
 */
export async function createAccount(label: string): Promise<Account> {
  const account = newAccount(label)
  const api = await request.newContext({ baseURL: BACKEND_IPV6 })
  try {
    await api.get('/api/v1/auth/csrf')
    const token = (await api.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
    const response = await api.post('/api/v1/auth/register', {
      headers: { 'X-XSRF-TOKEN': token },
      data: { email: account.email, password: account.password, displayName: account.displayName, adultConfirmed: true },
    })
    expect(response.status(), 'fixture registration').toBe(201)
  } finally {
    await api.dispose()
  }
  return account
}

/** Fills and submits the login form of the current page. */
export async function signIn(page: Page, account: Account): Promise<void> {
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Password', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

/** Opens the login page directly and signs in; ends on the home page. */
export async function signInFromHome(page: Page, account: Account): Promise<void> {
  await page.goto('/login')
  await signIn(page, account)
  await expect(page.getByRole('navigation', { name: 'Account' }).getByText(account.displayName)).toBeVisible()
}

/** Status codes of the profile updates the page sends (real responses, nothing is mocked). */
export function recordProfileUpdates(page: Page): number[] {
  const statuses: number[] = []
  page.on('response', (response) => {
    if (response.request().method() === 'PATCH' && response.url().endsWith('/api/v1/users/me')) {
      statuses.push(response.status())
    }
  })
  return statuses
}
