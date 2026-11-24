import { describe, expect, it } from 'vitest'
import { EUROPEAN_WHEEL_ORDER, numberColor, RED_NUMBERS } from './rouletteNumbers'

describe('European wheel numbers', () => {
  it('has exactly the 37 pockets 0–36, each once', () => {
    expect(EUROPEAN_WHEEL_ORDER).toHaveLength(37)
    expect([...EUROPEAN_WHEEL_ORDER].sort((a, b) => a - b)).toEqual(Array.from({ length: 37 }, (_, n) => n))
  })

  it('lists the pockets clockwise from zero in the single-zero wheel order', () => {
    expect(EUROPEAN_WHEEL_ORDER).toEqual([
      0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18,
      29, 7, 28, 12, 35, 3, 26,
    ])
    expect(Object.isFrozen(EUROPEAN_WHEEL_ORDER)).toBe(true)
  })

  it('colours 0 green and 18 numbers each red and black', () => {
    const colors = Array.from({ length: 37 }, (_, n) => numberColor(n))
    expect(colors.filter((color) => color === 'GREEN')).toHaveLength(1)
    expect(colors.filter((color) => color === 'RED')).toHaveLength(18)
    expect(colors.filter((color) => color === 'BLACK')).toHaveLength(18)
    expect(numberColor(0)).toBe('GREEN')
    expect([...RED_NUMBERS].sort((a, b) => a - b)).toEqual([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36])
    expect(numberColor(2)).toBe('BLACK')
    expect(numberColor(36)).toBe('RED')
  })

  it('alternates red and black around the wheel after zero, starting with red 32', () => {
    const around = EUROPEAN_WHEEL_ORDER.slice(1).map(numberColor)
    around.forEach((color, index) => expect(color, `pocket ${index + 1}`).toBe(index % 2 === 0 ? 'RED' : 'BLACK'))
  })

  it.each([-1, 37, 1.5, Number.NaN])('rejects %s', (value) => {
    expect(() => numberColor(value)).toThrow(RangeError)
  })
})
