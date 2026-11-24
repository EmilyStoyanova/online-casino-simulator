import { EUROPEAN_WHEEL_ORDER, numberColor } from '../../features/roulette/rouletteNumbers'
import type { RouletteNumberColor } from '../../types/roulette'

/**
 * Geometry and colours of the drawn wheel. Pure functions without Phaser, so they are unit-tested.
 *
 * Angles are in radians, clockwise, measured from the top (where the fixed pointer is). The wheel's
 * rotation `r` turns every pocket by `r`; pocket `i` is centred at `i × POCKET_ANGLE + r`.
 *
 * Nothing here chooses a number: the target of a spin is always a number the server returned.
 */

export const POCKET_COUNT = EUROPEAN_WHEEL_ORDER.length
export const POCKET_ANGLE = (2 * Math.PI) / POCKET_COUNT
const FULL_TURN = 2 * Math.PI

/** Full turns before the wheel settles on the server's number (visual only). */
export const SPIN_TURNS = 5
/** Duration of the settle animation in milliseconds (not used with reduced motion). */
export const SPIN_DURATION_MS = 4500

/** Colours of the dark theme (Tailwind zinc, rose, emerald and amber shades) as 0xRRGGBB. */
export const WHEEL_COLORS = {
  pocket: { RED: 0xbe123c, BLACK: 0x18181b, GREEN: 0x047857 } satisfies Record<RouletteNumberColor, number>,
  separator: 0xd4d4d8,
  rim: 0x27272a,
  rimEdge: 0x3f3f46,
  accent: 0xfbbf24,
  hub: 0x09090b,
  hubRing: 0x10b981,
  numeral: '#ffffff',
} as const

/** Radii as fractions of the wheel radius. */
export const WHEEL_RADII = {
  rim: 1,
  pocketsOuter: 0.93,
  pocketsInner: 0.66,
  numerals: 0.82,
  hub: 0.42,
  cap: 0.1,
} as const

/**
 * The rotation before any server result: the pointer sits on the separator between two pockets
 * (0 and 26), so the resting wheel never looks as if a number had been drawn.
 */
export const NEUTRAL_ROTATION = POCKET_ANGLE / 2

/** The position of a number on the wheel (0 for zero, 1 for 32, …). */
export function pocketIndex(value: number): number {
  numberColor(value) // validates 0–36
  return EUROPEAN_WHEEL_ORDER.indexOf(value)
}

/** The pockets in drawing order with their centre angle and colour. */
export function pockets(): { value: number; index: number; centre: number; color: RouletteNumberColor }[] {
  return EUROPEAN_WHEEL_ORDER.map((value, index) => ({ value, index, centre: index * POCKET_ANGLE, color: numberColor(value) }))
}

function normalize(angle: number): number {
  const turned = angle % FULL_TURN
  return turned < 0 ? turned + FULL_TURN : turned
}

/** The rotation (0 … 2π) that puts the pocket of `value` under the pointer at the top. */
export function restingRotation(value: number): number {
  return normalize(-pocketIndex(value) * POCKET_ANGLE)
}

/**
 * The rotation a spin ends at: clockwise from `current`, at least `turns` full turns, stopping with the
 * pocket of `value` under the pointer. Deterministic: the same inputs always give the same angle.
 */
export function spinTargetRotation(current: number, value: number, turns: number = SPIN_TURNS): number {
  const rest = restingRotation(value)
  return current + turns * FULL_TURN + normalize(rest - current)
}
