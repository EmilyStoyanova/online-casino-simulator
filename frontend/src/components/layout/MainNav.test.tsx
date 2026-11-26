import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { admin, alice, interestCatalog, startingWallet, upStatus } from '../../test/fixtures'
import { csrfEndpoint, deleteCookie, jsonResponse, noContent, noSession, problemResponse, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'

const CURRENT_USER = 'GET /api/v1/users/me'

type Handlers = Parameters<typeof stubApi>[0][string]

/**
 * The backend of these tests: no roulette route, so any such request fails the test. The wallet page's
 * first history page is answered as an empty history, so that page renders as for a new player.
 */
function backend(currentUser: Handlers = noSession()) {
  return stubApi({
    'GET /api/v1/auth/csrf': csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    [CURRENT_USER]: currentUser,
    'GET /api/v1/interests': () => jsonResponse(interestCatalog),
    'GET /api/v1/wallet': () => jsonResponse(startingWallet),
    'GET /api/v1/wallet/transactions?limit=20': () => jsonResponse({ items: [], nextCursor: null }),
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      return noContent()
    },
  })
}

function deferred() {
  let respond: (response: Response) => void = () => undefined
  const handler = () => new Promise<Response>((resolve) => (respond = resolve))
  return { handler, respond: (response: Response) => act(async () => respond(response)) }
}

const mainNav = () => screen.queryByRole('navigation', { name: 'Main' })
const accountNav = () => screen.getByRole('navigation', { name: 'Account' })

describe('Main navigation', () => {
  it('offers Lobby, Roulette and Wallet to a signed-in player, next to the unchanged account menu', async () => {
    backend(() => jsonResponse(alice))
    renderApp('/')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Lobby', '/'],
      ['Roulette', '/roulette'],
      ['Wallet', '/wallet'],
    ])
    expect(within(accountNav()).getByRole('link', { name: 'My profile' })).toHaveAttribute('href', '/profile')
    expect(within(accountNav()).getByText('Alice')).toBeInTheDocument()
    expect(within(accountNav()).getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    // The two navigations stay separate: no player link inside the account menu.
    expect(within(accountNav()).queryByRole('link', { name: 'Roulette' })).not.toBeInTheDocument()
  })

  it.each([
    ['/', 'Lobby'],
    ['/roulette', 'Roulette'],
    ['/wallet', 'Wallet'],
  ])('marks the current destination of %s with aria-current="page"', async (path, current) => {
    backend(() => jsonResponse(alice))
    renderApp(path)

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    for (const link of within(nav).getAllByRole('link')) {
      if (link.textContent === current) expect(link).toHaveAttribute('aria-current', 'page')
      else expect(link).not.toHaveAttribute('aria-current')
    }
  })

  it('marks no main destination on a page outside it (the profile)', async () => {
    backend(() => jsonResponse(alice))
    renderApp('/profile')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await screen.findByRole('heading', { name: 'My profile' })
    expect(within(nav).queryByRole('link', { current: 'page' })).not.toBeInTheDocument()
  })

  it('is operated with the keyboard: Tab reaches the links, Enter follows them', async () => {
    backend(() => jsonResponse(alice))
    renderApp('/')
    const user = userEvent.setup()
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const roulette = within(nav).getByRole('link', { name: 'Roulette' })
    await screen.findByRole('link', { name: 'Balance 1,000 coins' })

    // Language, balance and account menu come first in the header, then the main navigation (visual order = tab order).
    await user.tab() // skip link
    await user.tab() // brand
    await user.tab()
    expect(screen.getByRole('button', { name: 'EN English' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'BG Български' })).toHaveFocus()
    await user.tab() // balance
    await user.tab() // My profile
    await user.tab() // Sign out
    await user.tab()
    expect(within(nav).getByRole('link', { name: 'Lobby' })).toHaveFocus()
    await user.tab()
    expect(roulette).toHaveFocus()

    await user.keyboard('{Enter}')

    expect(await screen.findByRole('heading', { level: 1, name: 'European roulette' })).toBeInTheDocument()
    expect(roulette).toHaveAttribute('aria-current', 'page')
  })

  it('is not shown to an administrator, who keeps the account menu', async () => {
    backend(() => jsonResponse(admin))
    renderApp('/')

    expect(await within(accountNav()).findByRole('link', { name: 'My profile' })).toBeInTheDocument()
    expect(mainNav()).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Roulette' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Wallet' })).not.toBeInTheDocument()
  })

  it('is not shown to visitors, who keep Sign in and Create account', async () => {
    backend()
    renderApp('/')

    expect(await within(accountNav()).findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(within(accountNav()).getByRole('link', { name: 'Create account' })).toBeInTheDocument()
    expect(mainNav()).not.toBeInTheDocument()
  })

  it('is not shown while the session is being checked or cannot be checked', async () => {
    const check = deferred()
    backend(check.handler)
    renderApp('/')

    expect(within(accountNav()).getByText('Checking sign-in…')).toBeInTheDocument()
    expect(mainNav()).not.toBeInTheDocument()

    await check.respond(problemResponse(500, 'INTERNAL_ERROR'))

    expect(await screen.findByText(/could not be checked/)).toBeInTheDocument()
    expect(mainNav()).not.toBeInTheDocument()
  })

  it('disappears when the player signs out from the account menu', async () => {
    backend(() => jsonResponse(alice))
    renderApp('/wallet')
    await screen.findByRole('navigation', { name: 'Main' })

    await userEvent.setup().click(within(accountNav()).getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText(/You are signed out\./)).toBeInTheDocument()
    expect(mainNav()).not.toBeInTheDocument()
  })
})

describe('Skip link', () => {
  it('is the first tab stop and moves the focus to the page content without changing the page', async () => {
    backend()
    renderApp('/')
    const user = userEvent.setup()
    await within(accountNav()).findByRole('link', { name: 'Sign in' })

    await user.tab()
    const skip = screen.getByRole('link', { name: 'Skip to main content' })
    expect(skip).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(screen.getByRole('main')).toHaveFocus()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Learn the odds.')
  })
})
