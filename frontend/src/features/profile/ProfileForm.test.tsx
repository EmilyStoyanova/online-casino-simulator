import { act, fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { alice, interestCatalog, PASSWORD, upStatus } from '../../test/fixtures'
import {
  csrfEndpoint,
  deleteCookie,
  jsonResponse,
  noContent,
  noSession,
  problemResponse,
  signedIn,
  stubApi,
} from '../../test/http'
import { renderApp } from '../../test/renderApp'
import type { UserProfile } from '../../types/user'

const CURRENT_USER = 'GET /api/v1/users/me'
const UPDATE = 'PATCH /api/v1/users/me'
const CSRF = 'GET /api/v1/auth/csrf'

type Handlers = Parameters<typeof stubApi>[0][string]
type Handler = (init: RequestInit) => Response | Promise<Response>

const saved: UserProfile = { ...alice, interests: ['PROBABILITY', 'STATISTICS'] }

/** The server's answer to a PATCH: the saved profile with the sent changes (trimmed like the server). */
function echoUpdate(base: UserProfile = saved): Handler {
  return (init) => {
    const changes = JSON.parse(String(init.body)) as Partial<UserProfile>
    return jsonResponse({ ...base, ...changes, displayName: (changes.displayName ?? base.displayName).trim() })
  }
}

function backend({
  currentUser = () => jsonResponse(saved),
  update = echoUpdate(),
  interests = () => jsonResponse(interestCatalog),
}: { currentUser?: Handlers; update?: Handlers; interests?: Handlers } = {}) {
  return stubApi({
    [CSRF]: csrfEndpoint(),
    'GET /api/v1/system/status': () => jsonResponse(upStatus),
    'GET /api/v1/interests': interests,
    [CURRENT_USER]: currentUser,
    [UPDATE]: update,
    'POST /api/v1/auth/login': signedIn(saved),
    'POST /api/v1/auth/logout': () => {
      deleteCookie('XSRF-TOKEN')
      return noContent()
    },
  })
}

/** A response that the test sends later. */
function deferred() {
  let respond: (response: Response) => void = () => undefined
  const handler = () => new Promise<Response>((resolve) => (respond = resolve))
  return { handler, respond: (response: Response) => act(async () => respond(response)) }
}

async function openProfile() {
  renderApp('/profile')
  await screen.findByRole('heading', { name: 'My profile' })
  // The catalogue has arrived.
  await screen.findAllByRole('checkbox')
  return userEvent.setup()
}

const details = () => screen.getByRole('region', { name: 'Account details' })
const header = () => screen.getByRole('navigation', { name: 'Account' })
const nameInput = () => screen.getByLabelText('Display name')
const box = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) })
const saveButton = () => screen.getByRole('button', { name: 'Save changes' })

async function rename(user: ReturnType<typeof userEvent.setup>, value: string) {
  await user.clear(nameInput())
  await user.type(nameInput(), value)
}

async function signIn() {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
  await user.type(screen.getByLabelText('Password'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('Profile page: access', () => {
  it('sends an anonymous user to the login page and back to the profile afterwards', async () => {
    const api = backend({ currentUser: noSession() })
    renderApp('/profile')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Please sign in to open this page.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register?returnTo=%2Fprofile')
    expect(screen.queryByRole('heading', { name: 'My profile' })).not.toBeInTheDocument()

    await signIn()

    expect(await screen.findByRole('heading', { name: 'My profile' })).toBeInTheDocument()
    expect(within(details()).getByText('alice@example.com')).toBeInTheDocument()
    expect(api.requestsTo('POST /api/v1/auth/login')).toHaveLength(1)
  })

  it('restores the profile after a page reload without passing through the login page', async () => {
    const check = deferred()
    backend({ currentUser: check.handler })
    renderApp('/profile')

    expect(screen.getByRole('heading', { name: 'One moment' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My profile' })).not.toBeInTheDocument()

    await check.respond(jsonResponse(saved))

    expect(screen.getByRole('heading', { name: 'My profile' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('is reachable from the header and the account panel once signed in', async () => {
    backend()
    renderApp('/')
    const user = userEvent.setup()

    await user.click(await within(header()).findByRole('link', { name: 'My profile' }))
    expect(await screen.findByRole('heading', { name: 'My profile' })).toBeInTheDocument()
    expect(within(header()).getByRole('link', { name: 'My profile' })).toHaveAttribute('aria-current', 'page')

    await user.click(screen.getByRole('link', { name: /Online Casino Simulator/ }))
    await user.click(within(await screen.findByRole('region', { name: 'Your account' })).getByRole('link', { name: 'Edit profile' }))
    expect(await screen.findByRole('heading', { name: 'My profile' })).toBeInTheDocument()
  })

  it('offers no profile link while signed out', async () => {
    backend({ currentUser: noSession() })
    renderApp('/')

    expect(await within(header()).findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(within(header()).queryByRole('link', { name: 'My profile' })).not.toBeInTheDocument()
  })

  it('goes to the home page when the user signs out on the profile page', async () => {
    backend()
    const user = await openProfile()

    await user.click(within(header()).getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText(/You are signed out\./)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'My profile' })).not.toBeInTheDocument()
    expect(within(header()).queryByRole('link', { name: 'My profile' })).not.toBeInTheDocument()
  })
})

describe('Profile page: viewing and editing', () => {
  it('shows the saved profile; only the display name and the interests are editable', async () => {
    backend()
    await openProfile()

    expect(within(details()).getByText('Alice')).toBeInTheDocument()
    expect(within(details()).getByText('alice@example.com')).toBeInTheDocument()
    expect(within(details()).getByText('Player')).toBeInTheDocument()
    const savedInterests = within(details()).getByRole('list', { name: 'Saved interests' })
    expect(within(savedInterests).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Probability & odds',
      'Statistics & data',
    ])

    expect(nameInput()).toHaveValue('Alice')
    expect(box('Probability & odds')).toBeChecked()
    expect(box('Statistics & data')).toBeChecked()
    expect(box('History of games')).not.toBeChecked()
    expect(screen.getByText(/2 of 8 selected\./)).toBeInTheDocument()
    // Nothing else can be edited.
    expect(screen.getAllByRole('textbox')).toEqual([nameInput()])
    expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument()
    expect(screen.getByText('Your email, role and 18+ confirmation cannot be changed here.')).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()
  })

  it('saves a new display name and shows it in the header and account panel without a reload', async () => {
    const api = backend()
    const user = await openProfile()

    await rename(user, '  Alice B. ')
    expect(screen.getByText('You have unsaved changes.')).toBeInTheDocument()
    await user.click(saveButton())

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    // Only the changed property, with the CSRF token and the cookies.
    const [patch] = api.requestsTo(UPDATE)
    expect(patch?.body).toEqual({ displayName: 'Alice B.' })
    expect(patch?.headers['X-XSRF-TOKEN']).toBe('csrf-1')
    expect(api.fetchMock).toHaveBeenCalledWith('/api/v1/users/me', expect.objectContaining({ method: 'PATCH', credentials: 'include' }))
    // The form, the details and the header show the server's answer.
    expect(nameInput()).toHaveValue('Alice B.')
    expect(within(details()).getByText('Alice B.')).toBeInTheDocument()
    expect(within(header()).getByText('Alice B.')).toBeInTheDocument()
    expect(saveButton()).toBeDisabled()

    await user.click(screen.getByRole('link', { name: /Online Casino Simulator/ }))
    expect(within(await screen.findByRole('region', { name: 'Your account' })).getByText('Alice B.')).toBeInTheDocument()
    // No extra request was needed for that.
    expect(api.requestsTo(CURRENT_USER)).toHaveLength(1)
  })

  it('saves the interest selection only when the profile is saved, replacing the whole selection', async () => {
    const api = backend()
    const user = await openProfile()

    await user.click(box('Statistics & data'))
    await user.click(box('History of games'))
    await user.click(box('Mathematics of games'))
    expect(api.requestsTo(UPDATE)).toHaveLength(0)
    expect(within(details()).getByText('Statistics & data')).toBeInTheDocument()

    await user.click(saveButton())

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    expect(api.requestsTo(UPDATE)[0]?.body).toEqual({ interests: ['PROBABILITY', 'MATHEMATICS', 'HISTORY'] })
    const savedInterests = within(details()).getByRole('list', { name: 'Saved interests' })
    expect(within(savedInterests).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Probability & odds',
      'Mathematics of games',
      'History of games',
    ])
  })

  it('can remove every interest', async () => {
    const api = backend()
    const user = await openProfile()

    await user.click(box('Probability & odds'))
    await user.click(box('Statistics & data'))
    await user.click(saveButton())

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    expect(api.requestsTo(UPDATE)[0]?.body).toEqual({ interests: [] })
    expect(within(details()).getByText('None selected yet.')).toBeInTheDocument()
  })

  it('discards unsaved changes on request', async () => {
    const api = backend()
    const user = await openProfile()

    await rename(user, 'Someone else')
    await user.click(box('History of games'))
    await user.click(screen.getByRole('button', { name: 'Discard changes' }))

    expect(nameInput()).toHaveValue('Alice')
    expect(box('History of games')).not.toBeChecked()
    expect(saveButton()).toBeDisabled()
    expect(screen.queryByText('You have unsaved changes.')).not.toBeInTheDocument()
    expect(api.requestsTo(UPDATE)).toHaveLength(0)
  })

  it('checks the display name before sending and focuses it', async () => {
    const api = backend()
    const user = await openProfile()

    await user.clear(nameInput())
    await user.click(saveButton())
    expect(nameInput()).toHaveAccessibleDescription(expect.stringContaining('Enter a display name.'))
    expect(nameInput()).toHaveFocus()

    await user.type(nameInput(), 'Al')
    await user.click(saveButton())
    expect(nameInput()).toHaveAccessibleDescription(expect.stringContaining('Use 3 to 30 characters.'))
    expect(api.requestsTo(UPDATE)).toHaveLength(0)
  })

  it('shows the field errors of the server and keeps the edits', async () => {
    backend({
      update: () =>
        problemResponse(400, 'VALIDATION_FAILED', {
          errors: [
            { field: 'displayName', message: 'must not contain control characters' },
            { field: 'interests[2]', message: 'must be an active interest' },
          ],
        }),
    })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(box('History of games'))
    await user.click(saveButton())

    expect(await screen.findByText('Please correct the highlighted fields.')).toBeInTheDocument()
    expect(nameInput()).toHaveAccessibleDescription(expect.stringContaining('Must not contain control characters.'))
    expect(screen.getByRole('group', { name: 'Interests' })).toHaveAccessibleDescription(
      expect.stringContaining('Must be an active interest.'),
    )
    expect(nameInput()).toHaveFocus()
    expect(nameInput()).toHaveValue('Alice B.')
    expect(box('History of games')).toBeChecked()
    expect(within(details()).getByText('Alice')).toBeInTheDocument()

    // Editing a field removes its error.
    await user.type(nameInput(), 'x')
    expect(screen.queryByText('Must not contain control characters.')).not.toBeInTheDocument()
  })

  it('sends one request however often the form is submitted while saving', async () => {
    const save = deferred()
    const api = backend({ update: save.handler })
    const user = await openProfile()
    await rename(user, 'Alice B.')

    const form = saveButton().closest('form')
    if (!form) throw new Error('form not found')
    fireEvent.submit(form)
    fireEvent.submit(form)
    await act(async () => undefined)

    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
    expect(nameInput()).toBeDisabled()
    expect(box('History of games')).toBeDisabled()
    await save.respond(jsonResponse({ ...saved, displayName: 'Alice B.' }))

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    expect(api.requestsTo(UPDATE)).toHaveLength(1)
  })

  it('keeps the profile and the selection when the catalogue cannot be loaded, and never sends the interests', async () => {
    const api = backend({ interests: [() => problemResponse(500, 'INTERNAL_ERROR'), () => jsonResponse(interestCatalog)] })
    renderApp('/profile')
    const user = userEvent.setup()

    expect(await screen.findByText(/Your current selection is kept as it is: PROBABILITY, STATISTICS\./)).toBeInTheDocument()
    expect(within(details()).getByText('PROBABILITY')).toBeInTheDocument()

    await rename(user, 'Alice B.')
    await user.click(saveButton())
    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    expect(api.requestsTo(UPDATE)[0]?.body).toEqual({ displayName: 'Alice B.' })

    await user.click(screen.getByRole('button', { name: 'Load interests again' }))
    expect(await screen.findByRole('checkbox', { name: /Probability & odds/ })).toBeChecked()
    expect(within(details()).getByText('Probability & odds')).toBeInTheDocument()
  })
})

describe('Profile page: failures', () => {
  it('explains a concurrent change, saves nothing and keeps the edits, and loads the latest version on request', async () => {
    const changedElsewhere: UserProfile = { ...saved, displayName: 'Alice (phone)', interests: ['HISTORY'] }
    const api = backend({
      currentUser: [() => jsonResponse(saved), () => jsonResponse(changedElsewhere)],
      update: [() => problemResponse(409, 'CONCURRENT_MODIFICATION'), echoUpdate(changedElsewhere)],
    })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    expect(await screen.findByText(/Your profile was changed somewhere else/)).toBeInTheDocument()
    expect(nameInput()).toHaveValue('Alice B.')
    // Never retried automatically.
    expect(api.requestsTo(UPDATE)).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Load the latest saved version' }))

    expect(await screen.findByText(/The latest saved version is now shown/)).toBeInTheDocument()
    // The other change is shown everywhere; the user's edit stays, the untouched interests follow it.
    expect(within(details()).getByText('Alice (phone)')).toBeInTheDocument()
    expect(within(header()).getByText('Alice (phone)')).toBeInTheDocument()
    expect(nameInput()).toHaveValue('Alice B.')
    expect(box('History of games')).toBeChecked()
    expect(box('Probability & odds')).not.toBeChecked()

    await user.click(saveButton())

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    // Only the edited property: the interests changed elsewhere are not undone.
    expect(api.requestsTo(UPDATE)[1]?.body).toEqual({ displayName: 'Alice B.' })
    expect(within(header()).getByText('Alice B.')).toBeInTheDocument()
  })

  it('keeps the edits after a network error and saves them with "Try again"', async () => {
    const api = backend({ update: [() => Promise.reject(new TypeError('Failed to fetch')), echoUpdate()] })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The server could not be reached.')
    expect(alert).toHaveTextContent('still in the form')
    expect(nameInput()).toHaveValue('Alice B.')
    expect(within(header()).getByText('Alice')).toBeInTheDocument()

    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
    expect(api.requestsTo(UPDATE)).toHaveLength(2)
  })

  it('offers to try again after a server error', async () => {
    backend({ update: () => problemResponse(500, 'INTERNAL_ERROR') })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Saving failed on our side.')
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(nameInput()).toHaveValue('Alice B.')
  })

  it('renews a rejected CSRF token once; if that fails too, it explains and keeps the edits', async () => {
    const api = backend({
      update: [
        () => problemResponse(403, 'CSRF_TOKEN_INVALID'),
        () => problemResponse(403, 'CSRF_TOKEN_INVALID'),
        echoUpdate(),
      ],
    })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Your security token could not be renewed.')
    // One automatic retry with a new token (apiClient), never without one.
    expect(api.requestsTo(UPDATE).map((request) => request.headers['X-XSRF-TOKEN'])).toEqual(['csrf-1', 'csrf-2'])
    expect(nameInput()).toHaveValue('Alice B.')

    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Your profile has been saved.')).toBeInTheDocument()
  })

  it('goes to the login page when the session has ended and returns to the profile afterwards', async () => {
    backend({
      currentUser: () => jsonResponse(saved),
      update: [() => problemResponse(401, 'SESSION_EXPIRED'), echoUpdate()],
    })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Your session has ended. Sign in again to continue.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register?returnTo=%2Fprofile')

    await signIn()

    expect(await screen.findByRole('heading', { name: 'My profile' })).toBeInTheDocument()
  })

  it('treats a missing session during a save as signed out', async () => {
    backend({ update: () => problemResponse(401, 'AUTHENTICATION_REQUIRED') })
    const user = await openProfile()

    await rename(user, 'Alice B.')
    await user.click(saveButton())

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Please sign in to open this page.')).toBeInTheDocument()
    expect(within(header()).queryByText('Alice')).not.toBeInTheDocument()
  })
})
