import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RouletteBoard } from './RouletteBoard'
import type { BetCell } from './rouletteTable'

const CONTRACT_RED = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36])

function renderBoard({ amounts = new Map<string, number>(), lockedReason = null as string | null } = {}) {
  const onPlace = vi.fn<(cell: BetCell) => void>()
  render(<RouletteBoard amounts={amounts} onPlace={onPlace} lockedReason={lockedReason} />)
  return { onPlace }
}

const groupButtons = (name: string) => within(screen.getByRole('group', { name })).getAllByRole('button')

describe('AC-W5-06.1 / 06.7: cells and DOM order', () => {
  it('has the groups Numbers, Dozens and Outside bets with 37, 3 and 6 buttons, and no other button', () => {
    renderBoard()

    expect(groupButtons('Numbers')).toHaveLength(37)
    expect(groupButtons('Dozens')).toHaveLength(3)
    expect(groupButtons('Outside bets')).toHaveLength(6)
    expect(screen.getAllByRole('button')).toHaveLength(46)
    expect(screen.queryByRole('button', { name: /column|split|street|corner/i })).not.toBeInTheDocument()
  })

  it('keeps the DOM order 0, 1–36 ascending, the dozens, then the outside bets', () => {
    renderBoard()

    const labels = screen.getAllByRole('button').map((button) => button.querySelector('span')?.textContent)
    expect(labels).toEqual([
      ...Array.from({ length: 37 }, (_, number) => String(number)),
      '1st dozen',
      '2nd dozen',
      '3rd dozen',
      'Low',
      'Even',
      'Red',
      'Black',
      'Odd',
      'High',
    ])
  })

  it('every cell is a native button (Tab, Enter and Space work without extra code)', () => {
    renderBoard()
    for (const button of screen.getAllByRole('button')) {
      expect(button.tagName).toBe('BUTTON')
      expect(button).toHaveAttribute('type', 'button')
    }
  })
})

describe('AC-W5-06.2: colours and names', () => {
  it('colours 18 red and 18 black numbers as the contract lists them, and zero green', () => {
    renderBoard()

    groupButtons('Numbers').forEach((button, number) => {
      const expected = number === 0 ? 'GREEN' : CONTRACT_RED.has(number) ? 'RED' : 'BLACK'
      expect(button, String(number)).toHaveAttribute('data-color', expected)
      expect(button.className).toContain({ GREEN: 'bg-emerald-700', RED: 'bg-rose-700', BLACK: 'bg-zinc-900' }[expected])
      expect(button).toHaveAccessibleName(`${number}, ${expected.toLowerCase()}. Straight bet, pays 35 to 1.`)
    })
  })

  it('names the dozens and the outside bets with their range, bet type and odds', () => {
    renderBoard()

    expect(groupButtons('Dozens').map((button) => button.getAttribute('aria-label'))).toEqual([
      '1st dozen, 1 to 12. Dozen bet, pays 2 to 1.',
      '2nd dozen, 13 to 24. Dozen bet, pays 2 to 1.',
      '3rd dozen, 25 to 36. Dozen bet, pays 2 to 1.',
    ])
    expect(screen.getByRole('button', { name: 'Red. Red or black bet, pays 1 to 1.' })).toHaveAttribute('data-color', 'RED')
    expect(screen.getByRole('button', { name: 'Black. Red or black bet, pays 1 to 1.' })).toHaveAttribute('data-color', 'BLACK')
    expect(screen.getByRole('button', { name: 'Low, 1 to 18. Low or high bet, pays 1 to 1.' })).toHaveTextContent('Low1–18')
    expect(screen.getByRole('button', { name: 'High, 19 to 36. Low or high bet, pays 1 to 1.' })).toHaveTextContent('High19–36')
  })

  it('shows the visible odds of each group', () => {
    renderBoard()
    expect(screen.getByText(/straight bet, pays 35 to 1/)).toBeInTheDocument()
    expect(screen.getByText(/pay 2 to 1/)).toBeInTheDocument()
    expect(screen.getByText(/pay 1 to 1/)).toBeInTheDocument()
  })

  it('shows a placed amount on the cell as text and adds it to the name', () => {
    renderBoard({ amounts: new Map([['STRAIGHT:17', 1000], ['RED_BLACK:RED', 1]]) })

    const seventeen = screen.getByRole('button', { name: '17, black. Straight bet, pays 35 to 1. 1,000 coins placed.' })
    expect(seventeen).toHaveTextContent('171,000')
    expect(screen.getByRole('button', { name: 'Red. Red or black bet, pays 1 to 1. 1 coin placed.' })).toHaveTextContent('Red1')
    expect(screen.getByRole('button', { name: '18, red. Straight bet, pays 35 to 1.' })).toHaveTextContent(/^18$/)
  })
})

describe('placing bets', () => {
  it('reports the cell on click, Enter and Space', async () => {
    const { onPlace } = renderBoard()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /^17, black/ }))
    screen.getByRole('button', { name: /^2nd dozen/ }).focus()
    await user.keyboard('{Enter}')
    screen.getByRole('button', { name: /^Odd/ }).focus()
    await user.keyboard(' ')

    expect(onPlace.mock.calls.map(([cell]) => cell.key)).toEqual(['STRAIGHT:17', 'DOZEN:SECOND', 'EVEN_ODD:ODD'])
  })

  it('reaches every cell with Tab in DOM order', async () => {
    renderBoard()
    const user = userEvent.setup()
    const order: string[] = []
    for (let index = 0; index < 46; index += 1) {
      await user.tab()
      order.push(document.activeElement?.getAttribute('aria-label')?.split('.')[0] ?? '')
    }
    expect(order.slice(0, 3)).toEqual(['0, green', '1, red', '2, black'])
    expect(order[36]).toBe('36, red')
    expect(order.slice(37)).toEqual([
      '1st dozen, 1 to 12',
      '2nd dozen, 13 to 24',
      '3rd dozen, 25 to 36',
      'Low, 1 to 18',
      'Even',
      'Red',
      'Black',
      'Odd',
      'High, 19 to 36',
    ])
  })

  it('while locked, keeps every cell focusable but aria-disabled with the reason, and places nothing', async () => {
    const { onPlace } = renderBoard({ lockedReason: 'Your bets are being reviewed.' })
    const user = userEvent.setup()

    for (const button of screen.getAllByRole('button')) {
      expect(button).toHaveAttribute('aria-disabled', 'true')
      expect(button).not.toBeDisabled()
      expect(button).toHaveAccessibleDescription('Your bets are being reviewed.')
    }
    await user.click(screen.getByRole('button', { name: /^Red/ }))
    await user.tab()
    await user.keyboard('{Enter}')
    expect(onPlace).not.toHaveBeenCalled()
  })
})
