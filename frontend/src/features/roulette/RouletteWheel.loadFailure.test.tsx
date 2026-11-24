import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

/**
 * A failing dynamic import of the Phaser chunk (e.g. offline after a deployment). In its own file,
 * because `RouletteWheel` keeps the loaded chunk for the whole page once it has loaded.
 */
describe('RouletteWheel when the Phaser chunk cannot be loaded', () => {
  it('shows the notice with the description, and a later mount loads the chunk again', async () => {
    vi.doMock('../../games/roulette/createWheelGame', () => {
      throw new TypeError('Failed to fetch dynamically imported module')
    })
    const { RouletteWheel } = await import('./RouletteWheel')
    const { fakeWheel } = await import('../../test/fakeWheelGame')

    const first = render(<RouletteWheel />)

    expect(await screen.findByText(/cannot be shown in this browser/)).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(37)
    first.unmount()

    vi.doMock('../../games/roulette/createWheelGame', () => import('../../test/fakeWheelGame'))
    render(<RouletteWheel />)

    await waitFor(() => expect(fakeWheel.live()).toHaveLength(1))
    await waitFor(() => expect(screen.queryByText('Loading the wheel…')).not.toBeInTheDocument())
    expect(screen.queryByText(/cannot be shown/)).not.toBeInTheDocument()
  })
})
