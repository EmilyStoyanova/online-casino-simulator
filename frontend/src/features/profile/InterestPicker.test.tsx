import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { interestCatalog } from '../../test/fixtures'
import type { Interest } from '../../types/interest'
import { InterestPicker } from './InterestPicker'
import type { InterestCatalog } from './useInterests'

// The seeded catalogue has exactly 8 entries, so the maximum is tested with a larger one.
const largeCatalog: Interest[] = [
  ...interestCatalog,
  { code: 'CARD_COUNTING', name: 'Card counting', description: 'Why it is a myth in online games' },
]

function Picker({ catalog, initial = [], onRetry = () => undefined }: {
  catalog: InterestCatalog
  initial?: string[]
  onRetry?: () => void
}) {
  const [selected, setSelected] = useState<string[]>(initial)
  return (
    <>
      <InterestPicker id="picker" catalog={catalog} onRetryCatalog={onRetry} selected={selected} onChange={setSelected} />
      <output data-testid="selected">{selected.join(',')}</output>
    </>
  )
}

const selected = () => screen.getByTestId('selected').textContent
const box = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) })

describe('InterestPicker', () => {
  it('offers every interest of the catalogue as a labelled checkbox and keeps catalogue order', async () => {
    render(<Picker catalog={{ status: 'loaded', interests: interestCatalog }} />)
    const user = userEvent.setup()

    expect(screen.getByRole('group', { name: 'Interests' })).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(8)
    expect(screen.getByText(/Choose up to 8 topics/)).toBeInTheDocument()

    await user.click(box('History of games'))
    await user.click(box('Probability & odds'))
    // Keyboard: Space toggles the focused checkbox.
    box('Statistics & data').focus()
    await user.keyboard(' ')

    expect(box('History of games')).toBeChecked()
    expect(selected()).toBe('PROBABILITY,STATISTICS,HISTORY')
    expect(screen.getByText(/3 of 8 selected\./)).toBeInTheDocument()

    await user.click(box('Probability & odds'))
    expect(selected()).toBe('STATISTICS,HISTORY')
  })

  it('allows at most eight and explains why the others are disabled', async () => {
    render(<Picker catalog={{ status: 'loaded', interests: largeCatalog }} />)
    const user = userEvent.setup()

    for (const interest of largeCatalog.slice(0, 8)) {
      await user.click(box(interest.name))
    }

    expect(selected()?.split(',')).toHaveLength(8)
    expect(box('Card counting')).toBeDisabled()
    expect(screen.getByText(/8 of 8 selected\. You have reached the maximum/)).toBeInTheDocument()
    await user.click(box('Card counting'))
    expect(selected()).not.toContain('CARD_COUNTING')

    await user.click(box('History of games'))
    expect(box('Card counting')).toBeEnabled()
    await user.click(box('Card counting'))
    expect(selected()).toContain('CARD_COUNTING')
  })

  it('shows loading and empty catalogues', () => {
    const { rerender } = render(<Picker catalog={{ status: 'loading' }} />)
    expect(screen.getByText('Loading interests…')).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()

    rerender(<Picker catalog={{ status: 'loaded', interests: [] }} />)
    expect(screen.getByText('No interests are available at the moment.')).toBeInTheDocument()
  })

  it('keeps the selection when the catalogue cannot be loaded and offers to load it again', async () => {
    const onRetry = vi.fn()
    render(
      <Picker
        catalog={{ status: 'failed', message: 'The list of interests could not be loaded.' }}
        initial={['PROBABILITY', 'HISTORY']}
        onRetry={onRetry}
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent(
      'The list of interests could not be loaded. Your current selection is kept as it is: PROBABILITY, HISTORY.',
    )
    expect(selected()).toBe('PROBABILITY,HISTORY')

    await userEvent.setup().click(screen.getByRole('button', { name: 'Load interests again' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
