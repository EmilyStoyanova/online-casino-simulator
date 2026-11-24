import { vi } from 'vitest'
import type { WheelGame } from '../games/roulette/createWheelGame'

/**
 * Test double of `games/roulette/createWheelGame` (installed for every test in `setup.ts`): jsdom has no
 * canvas or WebGL, so Phaser itself is never loaded in unit tests. It records every game the
 * `RouletteWheel` component creates, so tests can check the lifecycle.
 */
export interface FakeWheelGame extends WheelGame {
  parent: HTMLElement
  options: { reducedMotion: boolean }
  spinTo: ReturnType<typeof vi.fn<WheelGame['spinTo']>>
  setReducedMotion: ReturnType<typeof vi.fn<WheelGame['setReducedMotion']>>
  destroy: ReturnType<typeof vi.fn<WheelGame['destroy']>>
}

export const fakeWheel = {
  instances: [] as FakeWheelGame[],
  /** When set, the next `createWheelGame` call throws it (e.g. a renderer that cannot start). */
  failWith: null as Error | null,
  /** What `canRenderWheel` answers (jsdom has no real canvas; installed in `setup.ts`). */
  canvasSupported: true,
  reset(): void {
    this.instances = []
    this.failWith = null
    this.canvasSupported = true
  },
  /** The games that have not been destroyed. */
  live(): FakeWheelGame[] {
    return this.instances.filter((instance) => instance.destroy.mock.calls.length === 0)
  },
}

export class WheelUnavailableError extends Error {}

export function createWheelGame(parent: HTMLElement, options: { reducedMotion: boolean }): WheelGame {
  if (fakeWheel.failWith) throw fakeWheel.failWith
  const instance: FakeWheelGame = {
    parent,
    options,
    ready: Promise.resolve(),
    spinTo: vi.fn<WheelGame['spinTo']>(() => Promise.resolve()),
    setReducedMotion: vi.fn<WheelGame['setReducedMotion']>(),
    destroy: vi.fn<WheelGame['destroy']>(),
  }
  fakeWheel.instances.push(instance)
  return instance
}
