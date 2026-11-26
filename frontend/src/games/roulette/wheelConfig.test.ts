import { describe, expect, it, vi } from 'vitest'
import { EUROPEAN_WHEEL_ORDER } from '../../features/roulette/rouletteNumbers'
import {
  NEUTRAL_ROTATION,
  POCKET_ANGLE,
  POCKET_COUNT,
  pocketIndex,
  pockets,
  restingRotation,
  SPIN_TURNS,
  spinTargetRotation,
} from './wheelConfig'

const FULL = 2 * Math.PI
/** Angle 0 … 2π. */
const norm = (angle: number) => ((angle % FULL) + FULL) % FULL
/** The pocket under the pointer for a wheel rotation. */
function pocketAtPointer(rotation: number): number {
  // Pocket i is centred at i × step + rotation; the pointer is at angle 0 (top).
  const index = Math.round(norm(-rotation) / POCKET_ANGLE) % POCKET_COUNT
  return EUROPEAN_WHEEL_ORDER[index] ?? -1
}

describe('wheel geometry', () => {
  it('has 37 equal pockets in wheel order, zero centred at the top', () => {
    const drawn = pockets()
    expect(drawn.map((pocket) => pocket.value)).toEqual(EUROPEAN_WHEEL_ORDER)
    expect(drawn[0]).toEqual({ value: 0, index: 0, centre: 0, color: 'GREEN' })
    expect(drawn[1]).toMatchObject({ value: 32, color: 'RED' })
    expect(drawn[2]).toMatchObject({ value: 15, color: 'BLACK' })
    expect(POCKET_COUNT * POCKET_ANGLE).toBeCloseTo(FULL)
  })

  it('knows the position of every number', () => {
    expect(pocketIndex(0)).toBe(0)
    expect(pocketIndex(32)).toBe(1)
    expect(pocketIndex(26)).toBe(36)
    expect(() => pocketIndex(37)).toThrow(RangeError)
    expect(() => pocketIndex(-1)).toThrow(RangeError)
  })

  it.each(Array.from({ length: 37 }, (_, n) => n))('rests with %i under the pointer', (value) => {
    const rotation = restingRotation(value)
    expect(rotation).toBeGreaterThanOrEqual(0)
    expect(rotation).toBeLessThan(FULL)
    expect(pocketAtPointer(rotation)).toBe(value)
  })

  it('spins clockwise at least the configured turns and stops on the server’s number', () => {
    for (const start of [0, 1.2345, -3, 40]) {
      for (const value of [0, 17, 26, 32]) {
        const target = spinTargetRotation(start, value)
        expect(target - start).toBeGreaterThanOrEqual(SPIN_TURNS * FULL)
        expect(target - start).toBeLessThan((SPIN_TURNS + 1) * FULL)
        expect(pocketAtPointer(target)).toBe(value)
      }
    }
  })

  it('rests before any result with the pointer on a separator, not on a number', () => {
    // Pocket i spans i × step ± step/2 (plus the rotation); the pointer is at angle 0.
    const offset = norm(-NEUTRAL_ROTATION) / POCKET_ANGLE
    expect(offset % 1).toBeCloseTo(0.5) // exactly between two pocket centres
    for (const { centre } of pockets()) {
      const distance = Math.abs(((norm(centre + NEUTRAL_ROTATION) + Math.PI) % FULL) - Math.PI)
      expect(distance).toBeGreaterThanOrEqual(POCKET_ANGLE / 2 - 1e-9)
    }
    // A spin from the neutral position still ends on the server's number.
    expect(pocketAtPointer(spinTargetRotation(NEUTRAL_ROTATION, 17))).toBe(17)
  })

  it('is deterministic and uses no random source', () => {
    const random = vi.spyOn(Math, 'random')

    expect(spinTargetRotation(0.5, 17)).toBe(spinTargetRotation(0.5, 17))
    expect(restingRotation(17)).toBe(restingRotation(17))
    expect(random).not.toHaveBeenCalled()
    expect(() => spinTargetRotation(0, 37)).toThrow(RangeError)
  })
})
