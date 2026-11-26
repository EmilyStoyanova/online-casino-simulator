import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef, StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fakeWheel } from '../../test/fakeWheelGame'
import { alice, startingWallet, upStatus } from '../../test/fixtures'
import { jsonResponse, stubApi } from '../../test/http'
import { renderApp } from '../../test/renderApp'
import { EUROPEAN_WHEEL_ORDER } from './rouletteNumbers'
import { RouletteWheel, type RouletteWheelHandle } from './RouletteWheel'

/** A `matchMedia` whose reduced-motion answer the test can change. */
function stubReducedMotion(initial: boolean) {
  let matches = initial
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    get matches() {
      return matches
    },
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
  }))
  return {
    set: (value: boolean) =>
      act(() => {
        matches = value
        listeners.forEach((listener) => listener())
      }),
    listenerCount: () => listeners.size,
  }
}

const canvasHost = () => screen.getByTestId('roulette-wheel-canvas')

describe('RouletteWheel', () => {
  it('creates one Phaser game inside its decorative canvas host and hides the loading note when drawn', async () => {
    render(<RouletteWheel />)

    expect(screen.getByText('Loading the wheel…')).toBeInTheDocument()
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(1))
    expect(fakeWheel.instances[0]?.parent).toBe(canvasHost())
    expect(fakeWheel.instances[0]?.options).toEqual({ reducedMotion: false })
    expect(canvasHost()).toHaveAttribute('aria-hidden', 'true')
    await waitFor(() => expect(screen.queryByText('Loading the wheel…')).not.toBeInTheDocument())
  })

  it('destroys its game exactly once when it unmounts', async () => {
    const { unmount } = render(<RouletteWheel />)
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(1))

    unmount()

    expect(fakeWheel.instances[0]?.destroy).toHaveBeenCalledTimes(1)
    expect(fakeWheel.live()).toHaveLength(0)
  })

  it('creates a single game under StrictMode’s trial mount and leaves none after unmount', async () => {
    const { unmount } = render(
      <StrictMode>
        <RouletteWheel />
      </StrictMode>,
    )
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    await act(async () => {
      await Promise.resolve()
    })

    expect(fakeWheel.instances).toHaveLength(1)
    unmount()
    expect(fakeWheel.live()).toHaveLength(0)
  })

  it('does not create a game when it unmounts before Phaser has loaded', async () => {
    const { unmount } = render(<RouletteWheel />)

    unmount()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    expect(fakeWheel.instances).toHaveLength(0)
  })

  it('is torn down when the player leaves /roulette and created anew on return', async () => {
    stubApi({
      'GET /api/v1/users/me': () => jsonResponse(alice),
      'GET /api/v1/wallet': () => jsonResponse(startingWallet),
      'GET /api/v1/system/status': () => jsonResponse(upStatus),
    })
    renderApp('/roulette')
    const user = userEvent.setup()
    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    const main = () => screen.getByRole('navigation', { name: 'Main' })

    await user.click(within(main()).getByRole('link', { name: 'Lobby' }))
    await screen.findByRole('heading', { level: 1, name: 'Welcome back, Alice' })
    expect(fakeWheel.live()).toHaveLength(0)
    expect(fakeWheel.instances[0]?.destroy).toHaveBeenCalledTimes(1)

    await user.click(within(main()).getByRole('link', { name: 'Roulette' }))
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(2))
    expect(fakeWheel.live()).toHaveLength(1)
  })

  it('describes the wheel in HTML: caption and all 37 pockets in order with their colours', async () => {
    render(<RouletteWheel />)

    const figure = screen.getByRole('figure', { name: /European single-zero wheel: 37 pockets, 0 to 36/ })
    const pocketsList = within(figure).getByRole('list', { name: 'Pockets clockwise from zero' })
    const items = within(pocketsList).getAllByRole('listitem')
    expect(items.map((item) => item.textContent)).toEqual(
      EUROPEAN_WHEEL_ORDER.map((value) => `${value} ${value === 0 ? 'green' : [32, 19, 21, 25, 34, 27, 36, 30, 23, 5, 16, 1, 14, 9, 18, 7, 12, 3].includes(value) ? 'red' : 'black'}`),
    )
    // Nothing to play with: the wheel offers no button.
    expect(within(figure).queryByRole('button')).not.toBeInTheDocument()
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(1))
  })

  it('keeps the description and explains the missing picture when the wheel cannot be drawn', async () => {
    fakeWheel.failWith = new Error('no canvas')
    render(<RouletteWheel />)

    expect(await screen.findByText(/cannot be shown in this browser/)).toBeInTheDocument()
    expect(screen.queryByText('Loading the wheel…')).not.toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(37)
    expect(fakeWheel.live()).toHaveLength(0)
  })

  it('without canvas support shows the notice before loading Phaser at all', async () => {
    fakeWheel.canvasSupported = false
    render(<RouletteWheel />)

    expect(await screen.findByText(/cannot be shown in this browser/)).toBeInTheDocument()
    expect(fakeWheel.instances).toHaveLength(0)
    expect(screen.getAllByRole('listitem')).toHaveLength(37)
  })

  it('starts with the reduced-motion preference and passes later changes to the game', async () => {
    const motion = stubReducedMotion(true)
    const { unmount } = render(<RouletteWheel />)
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(1))
    const game = fakeWheel.instances[0]
    expect(game?.options).toEqual({ reducedMotion: true })

    await motion.set(false)
    expect(game?.setReducedMotion).toHaveBeenLastCalledWith(false)
    await motion.set(true)
    expect(game?.setReducedMotion).toHaveBeenLastCalledWith(true)

    unmount()
    expect(motion.listenerCount()).toBe(0)
  })

  it('forwards a server number to the game through its handle, and refuses once unmounted', async () => {
    const wheel = createRef<RouletteWheelHandle>()
    const { unmount } = render(<RouletteWheel ref={wheel} />)
    await waitFor(() => expect(fakeWheel.instances).toHaveLength(1))

    await act(async () => {
      await wheel.current?.spinTo(17)
    })
    expect(fakeWheel.instances[0]?.spinTo).toHaveBeenCalledWith(17)

    const handle = wheel.current
    unmount()
    await expect(handle?.spinTo(17)).rejects.toThrow('The wheel is not shown.')
  })
})

describe('No client-side outcome', () => {
  it('no roulette or game source file uses a random source', () => {
    const sources = {
      ...import.meta.glob('../../games/**/*.ts', { query: '?raw', import: 'default', eager: true }),
      ...import.meta.glob('./**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true }),
    } as Record<string, string>
    const production = Object.entries(sources).filter(([path]) => !/\.test\.tsx?$/.test(path))

    expect(production.length).toBeGreaterThanOrEqual(6)
    for (const [path, source] of production) {
      expect(source, path).not.toMatch(/Math\.random|getRandomValues|randomUUID|RandomDataGenerator|Phaser\.Math\.RND|\bRND\b/)
    }
  })
})
