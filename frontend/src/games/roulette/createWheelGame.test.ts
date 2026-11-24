import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The real `createWheelGame` with a stand-in for Phaser (jsdom has no canvas/WebGL). The stand-in's
 * `start()` does what Phaser 4.2.1's `Game#start` does through its `VisibilityHandler`: it adds a
 * `visibilitychange` listener to `document` and assigns `window.onblur`/`window.onfocus`. Its
 * `destroy()` only records the call, like Phaser, which defers the real work to the next frame.
 */
const phaser = vi.hoisted(() => {
  const handlers = {
    onVisibility: () => undefined,
    onBlur: () => undefined,
    onFocus: () => undefined,
  }
  class FakeGame {
    static instances: FakeGame[] = []
    readonly config: Record<string, unknown>
    readonly destroyCalls: boolean[] = []
    constructor(config: Record<string, unknown>) {
      this.config = config
      FakeGame.instances.push(this)
    }
    destroy(removeCanvas: boolean) {
      this.destroyCalls.push(removeCanvas)
    }
    start() {
      document.addEventListener('visibilitychange', handlers.onVisibility, false)
      window.onblur = handlers.onBlur
      window.onfocus = handlers.onFocus
    }
  }
  return { FakeGame, handlers }
})

const scenes = vi.hoisted(() => ({
  instances: [] as {
    options: { reducedMotion: boolean; onReady: () => void }
    spinTo: ReturnType<typeof vi.fn>
    setReducedMotion: ReturnType<typeof vi.fn>
  }[],
}))

const canvas = vi.hoisted(() => ({ supported: true }))

vi.mock('phaser', () => ({ AUTO: 0, Game: phaser.FakeGame, Scale: { RESIZE: 3 } }))
vi.mock('./canvasSupport', () => ({ canRenderWheel: () => canvas.supported }))
vi.mock('./RouletteScene', () => ({
  RouletteScene: class {
    options: { reducedMotion: boolean; onReady: () => void }
    spinTo = vi.fn(() => Promise.resolve())
    setReducedMotion = vi.fn()
    constructor(options: { reducedMotion: boolean; onReady: () => void }) {
      this.options = options
      scenes.instances.push(this)
    }
  },
}))

// `setup.ts` replaces this module for every other test; here the real one is under test.
const { createWheelGame, WheelUnavailableError } =
  await vi.importActual<typeof import('./createWheelGame')>('./createWheelGame')

type FakeGame = InstanceType<typeof phaser.FakeGame>

function create(reducedMotion = false) {
  const parent = document.createElement('div')
  const wheel = createWheelGame(parent, { reducedMotion })
  const game = phaser.FakeGame.instances.at(-1) as FakeGame
  const scene = scenes.instances.at(-1)
  if (!scene) throw new Error('no scene')
  return { parent, wheel, game, scene, start: () => game.start(), draw: () => scene.options.onReady() }
}

afterEach(() => {
  phaser.FakeGame.instances = []
  scenes.instances = []
  canvas.supported = true
  window.onblur = null
  window.onfocus = null
})

describe('createWheelGame', () => {
  it('configures a purely visual game: no input managers, no audio, no banner, no focus, sized by its parent', () => {
    const { parent, game, scene } = create(true)

    expect(game.config).toMatchObject({
      type: 0,
      parent,
      transparent: true,
      banner: false,
      autoFocus: false,
      input: { keyboard: false, mouse: false, touch: false, gamepad: false, windowEvents: false },
      audio: { noAudio: true },
      scale: { mode: 3, width: 320, height: 320 },
      scene,
    })
    expect(scene.options.reducedMotion).toBe(true)
  })

  it('creates no game without canvas support', () => {
    canvas.supported = false

    expect(() => createWheelGame(document.createElement('div'), { reducedMotion: false })).toThrow(WheelUnavailableError)
    expect(phaser.FakeGame.instances).toHaveLength(0)
  })

  it('validates the number at once and forwards a spin only after the wheel is drawn', async () => {
    const { wheel, scene, draw } = create()

    await expect(wheel.spinTo(37)).rejects.toThrow(RangeError)
    const spin = wheel.spinTo(17)
    await Promise.resolve()
    expect(scene.spinTo).not.toHaveBeenCalled()

    draw()
    await spin
    expect(scene.spinTo).toHaveBeenCalledWith(17)
  })

  it('destroys once (Phaser removes the canvas on its next frame) and refuses spins afterwards', async () => {
    const { wheel, game, scene, draw } = create()
    draw()

    wheel.destroy()
    wheel.destroy()

    expect(game.destroyCalls).toEqual([true])
    await expect(wheel.spinTo(5)).rejects.toThrow('The wheel is not shown.')
    wheel.setReducedMotion(true)
    expect(scene.setReducedMotion).not.toHaveBeenCalled()
  })

  it('settles a spin that waits for a wheel destroyed before it was drawn', async () => {
    const { wheel, scene } = create()
    const waiting = wheel.spinTo(3)

    wheel.destroy()

    await expect(waiting).rejects.toThrow('The wheel is not shown.')
    expect(scene.spinTo).not.toHaveBeenCalled()
  })

  it('passes reduced-motion changes to the scene, also before it is drawn', () => {
    const { wheel, scene } = create()

    wheel.setReducedMotion(true)

    expect(scene.setReducedMotion).toHaveBeenCalledWith(true)
  })
})

describe('createWheelGame: handlers Phaser leaves on document and window', () => {
  it('removes the visibilitychange listener and the blur/focus handlers on destroy', () => {
    const onVisibility = vi.spyOn(phaser.handlers, 'onVisibility')
    const { wheel, start } = create()
    start()
    expect(window.onblur).toBe(phaser.handlers.onBlur)

    wheel.destroy()

    document.dispatchEvent(new Event('visibilitychange'))
    expect(onVisibility).not.toHaveBeenCalled()
    expect(window.onblur).toBeNull()
    expect(window.onfocus).toBeNull()
  })

  it('keeps blur/focus handlers that someone else assigned meanwhile', () => {
    const { wheel, start } = create()
    start()
    const other = () => undefined
    window.onblur = other

    wheel.destroy()

    expect(window.onblur).toBe(other)
    expect(window.onfocus).toBeNull()
  })

  it('cleans up at once when Phaser starts a game that was destroyed before it started', () => {
    const onVisibility = vi.spyOn(phaser.handlers, 'onVisibility')
    const { wheel, start } = create()

    wheel.destroy()
    start() // Phaser still starts the loop to run the pending destroy

    document.dispatchEvent(new Event('visibilitychange'))
    expect(onVisibility).not.toHaveBeenCalled()
    expect(window.onblur).toBeNull()
  })

  it('removes only its own listener: other visibilitychange listeners keep working', () => {
    const othersBefore = vi.fn()
    const othersAfter = vi.fn()
    document.addEventListener('visibilitychange', othersBefore)
    const { wheel, start } = create()
    start()
    document.addEventListener('visibilitychange', othersAfter)

    wheel.destroy()
    document.dispatchEvent(new Event('visibilitychange'))

    expect(othersBefore).toHaveBeenCalledTimes(1)
    expect(othersAfter).toHaveBeenCalledTimes(1)
    document.removeEventListener('visibilitychange', othersBefore)
    document.removeEventListener('visibilitychange', othersAfter)
  })

  it('gives back the blur/focus handlers that were there before Phaser overwrote them', () => {
    const pageBlur = () => undefined
    const pageFocus = () => undefined
    window.onblur = pageBlur
    window.onfocus = pageFocus
    const { wheel, start } = create()
    start()
    expect(window.onblur).toBe(phaser.handlers.onBlur)

    wheel.destroy()

    expect(window.onblur).toBe(pageBlur)
    expect(window.onfocus).toBe(pageFocus)
  })

  it('restores document.addEventListener exactly: the inherited method, with no own property left behind', () => {
    const inherited = EventTarget.prototype.addEventListener
    const { start } = create()

    start()

    expect(Object.prototype.hasOwnProperty.call(document, 'addEventListener')).toBe(false)
    expect(document.addEventListener).toBe(inherited)
  })

  it('keeps a document.addEventListener that other code had installed on document itself', () => {
    const patched = vi.fn(EventTarget.prototype.addEventListener)
    document.addEventListener = patched
    try {
      const { start } = create()
      start()

      expect(document.addEventListener).toBe(patched)
      expect(patched).toHaveBeenCalledWith('visibilitychange', phaser.handlers.onVisibility, false)
    } finally {
      delete (document as Partial<Document>).addEventListener
    }
  })
})
