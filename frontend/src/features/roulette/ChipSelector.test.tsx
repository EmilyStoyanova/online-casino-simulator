import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { DEFAULT_CHIP, type ChipValue } from './betSlipReducer'
import { ChipSelector } from './ChipSelector'

function Harness() {
  const [value, setValue] = useState<ChipValue>(DEFAULT_CHIP)
  return <ChipSelector value={value} onChange={setValue} />
}

const group = () => screen.getByRole('group', { name: 'Chip value' })
const checked = () => within(group()).getAllByRole('radio').find((radio) => (radio as HTMLInputElement).checked)

describe('AC-W5-06.3: ChipSelector', () => {
  it('is a native radio group "Chip value" with the six chips, 10 coins chosen by default', () => {
    render(<Harness />)

    const radios = within(group()).getAllByRole('radio')
    const names = ['1 coin', '5 coins', '10 coins', '25 coins', '100 coins', '500 coins']
    expect(radios).toHaveLength(6)
    radios.forEach((radio, index) => expect(radio).toHaveAccessibleName(names[index]))
    expect(screen.getByRole('radio', { name: '10 coins' })).toBeChecked()
    expect(group()).toHaveAccessibleDescription('Each choice on the table adds 10 coins.')
    expect(group().tagName).toBe('FIELDSET')
    expect(new Set(radios.map((radio) => radio.getAttribute('name'))).size).toBe(1)
  })

  it('moves the selection with the arrow keys and names the chosen value in text', async () => {
    render(<Harness />)
    const user = userEvent.setup()

    await user.tab()
    expect(screen.getByRole('radio', { name: '10 coins' })).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(checked()).toHaveAccessibleName('25 coins')
    expect(screen.getByRole('radio', { name: '25 coins' })).toHaveFocus()
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(checked()).toHaveAccessibleName('500 coins')
    await user.keyboard('{ArrowLeft}')
    expect(checked()).toHaveAccessibleName('100 coins')
    await user.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}{ArrowUp}')
    expect(checked()).toHaveAccessibleName('1 coin')
    expect(group()).toHaveAccessibleDescription('Each choice on the table adds 1 coin.')
  })

  it('is one tab stop: Tab leaves the group after the chosen chip', async () => {
    render(
      <>
        <Harness />
        <button type="button">After</button>
      </>,
    )
    const user = userEvent.setup()

    await user.tab()
    await user.tab()
    expect(screen.getByRole('button', { name: 'After' })).toHaveFocus()
  })

  it('selects a chip with a click, and shows no currency symbol', async () => {
    render(<Harness />)

    await userEvent.setup().click(screen.getByText('500', { ignore: '.sr-only' }))

    expect(screen.getByRole('radio', { name: '500 coins' })).toBeChecked()
    expect(group().textContent).not.toMatch(/[$€£¥]|BGN|EUR|USD/)
  })
})
