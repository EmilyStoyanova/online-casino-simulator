import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { OddsInfo } from './OddsInfo'

const panel = () => screen.getByText('Odds and rules').closest('details') as HTMLDetailsElement

describe('AC-W5-08.5: Odds and rules', () => {
  it('is a native disclosure (details/summary), closed at first, reached with Tab and opened by its summary', async () => {
    render(<OddsInfo />)
    const user = userEvent.setup()
    const summary = screen.getByText('Odds and rules').closest('summary') as HTMLElement
    expect(panel().tagName).toBe('DETAILS')
    expect(panel().open).toBe(false)

    await user.tab()
    expect(summary).toHaveFocus()
    // jsdom does not run the browser's Enter/Space activation of <summary>; Chrome does (W5-08 walkthrough).
    await user.click(summary)

    expect(panel().open).toBe(true)
  })

  it('lists the five categories with what they cover, the chance of a win, the odds and what a win returns', () => {
    render(<OddsInfo />)
    const types = within(screen.getByRole('list', { name: 'Bet types' })).getAllByRole('listitem')

    expect(types.map((item) => within(item).getByRole('heading').textContent)).toEqual([
      'Straight bet',
      'Red or black',
      'Even or odd',
      'Low or high',
      'Dozen',
    ])
    expect(types[0]).toHaveTextContent('CoversOne number, 0 to 36Chance of a win1 in 37 (about 2.70 %)Odds35 to 1A win returns36 × the stake (stake included)')
    expect(types[1]).toHaveTextContent('Chance of a win18 in 37 (about 48.65 %)Odds1 to 1A win returns2 × the stake')
    expect(types[4]).toHaveTextContent('Covers1–12, 13–24 or 25–36Chance of a win12 in 37 (about 32.43 %)Odds2 to 1A win returns3 × the stake')
    expect(panel()).not.toHaveTextContent(/column|split|street|corner/i)
  })

  it('explains zero, the house edge of 1/37 and that spins are independent', () => {
    render(<OddsInfo />)
    expect(screen.getByRole('heading', { name: 'Zero' }).nextElementSibling).toHaveTextContent(/wins only a straight bet on 0/)
    expect(screen.getByRole('heading', { name: 'House edge' }).nextElementSibling).toHaveTextContent(
      'Every bet has the same house edge of 1/37, about 2.70 %: on average, 36/37 of all stakes (about 97.30 %) comes back.',
    )
    expect(panel()).toHaveTextContent('Every spin is independent: earlier numbers do not make any number more or less likely.')
    expect(panel()).toHaveTextContent('the server computes every result and payout')
  })
})
