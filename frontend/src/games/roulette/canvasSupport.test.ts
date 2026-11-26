import { describe, expect, it, vi } from 'vitest'

// `setup.ts` replaces this module for every other test; here the real one is under test.
const { canRenderWheel } = await vi.importActual<typeof import('./canvasSupport')>('./canvasSupport')

describe('canRenderWheel', () => {
  it('is true when a 2D canvas context exists, and asks for no WebGL context', () => {
    const getContext = vi
      .spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockImplementation(() => ({}) as CanvasRenderingContext2D)

    expect(canRenderWheel()).toBe(true)
    expect(getContext.mock.calls.map(([kind]) => kind)).toEqual(['2d'])
  })

  it('is false without a canvas context (null, or undefined as in environments without canvas)', () => {
    const getContext = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => null)
    expect(canRenderWheel()).toBe(false)

    getContext.mockImplementation(() => undefined as unknown as null)
    expect(canRenderWheel()).toBe(false)
  })

  it('is false when the canvas throws', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => {
      throw new Error('blocked')
    })

    expect(canRenderWheel()).toBe(false)
  })
})
