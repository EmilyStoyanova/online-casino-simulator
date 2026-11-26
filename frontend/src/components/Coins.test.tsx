import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Coins } from './Coins'

describe('Coins', () => {
  it.each([
    [0, '0 coins'],
    [1, '1 coin'],
    [1330, '1,330 coins'],
    [9_007_199_254_740_991, '9,007,199,254,740,991 coins'],
  ])('shows %i as "%s" with tabular digits and no currency symbol', (amount, text) => {
    render(<Coins amount={amount} />)

    const element = screen.getByText(text)
    expect(element).toHaveClass('tabular-nums')
    expect(element.textContent).not.toMatch(/[€$£¥]/)
  })
})
