import { AUTO, Game, Scale, type Types } from 'phaser'
import { canRenderWheel } from './canvasSupport'
import { RouletteScene } from './RouletteScene'
import { pocketIndex } from './wheelConfig'

/** The browser cannot draw the wheel (no canvas). The page shows its text description instead. */
export class WheelUnavailableError extends Error {
  constructor() {
    super('This browser cannot draw the roulette wheel.')
    this.name = 'WheelUnavailableError'
  }
}

/** The React side's handle on one Phaser game showing the wheel. */
export interface WheelGame {
  /** Resolves when the wheel is drawn (never, if the game is destroyed first). */
  ready: Promise<void>
  /**
   * Turns the wheel to the server's `winningNumber` (0–36); resolves when it rests, also when the game
   * is destroyed during the turn. Rejects with a `RangeError` for anything else, and with an `Error`
   * once the game is destroyed (or destroyed before it was drawn). The game never chooses a number.
   */
  spinTo: (winningNumber: number) => Promise<void>
  setReducedMotion: (reducedMotion: boolean) => void
  /** Stops the game; Phaser removes the canvas on its next frame. Safe to call more than once. */
  destroy: () => void
}

/**
 * `Phaser.Game` that cleans up after its `VisibilityHandler`. In Phaser 4.2.1, `Game#start` adds a
 * `visibilitychange` listener to `document` and assigns `window.onblur`/`window.onfocus`, and
 * `Game#destroy` never removes them: every game left one listener behind (measured in Chrome during
 * the W5-P01 review). This subclass records what `start` adds and removes it on `destroy`. Phaser
 * itself is not modified.
 *
 * It removes only what this game's `start` added, and it gives `window.onblur`/`window.onfocus` back
 * the values they had before Phaser overwrote them, but only while they still hold this game's
 * handlers: a handler assigned later by other code is left alone.
 */
class WheelPhaserGame extends Game {
  private readonly addedToDocument: Parameters<Document['addEventListener']>[] = []
  private readonly windowHandlers: { name: 'onblur' | 'onfocus'; before: Window['onblur']; assigned: Window['onblur'] }[] = []
  /** Set by `removeGlobalHandlers`; a `start` that runs afterwards cleans up at once. */
  private released = false

  protected override start(): void {
    const ownProperty = Object.prototype.hasOwnProperty.call(document, 'addEventListener')
    const add = document.addEventListener
    const record = this.addedToDocument
    const before = { onblur: window.onblur, onfocus: window.onfocus }
    // Records only the calls made while Phaser's `start` runs; it is synchronous, so no other code
    // runs while the method is replaced.
    document.addEventListener = function (this: Document, ...args: Parameters<Document['addEventListener']>) {
      record.push(args)
      add.apply(this, args)
    } as Document['addEventListener']
    try {
      super.start()
    } finally {
      // Back to exactly what it was: usually the inherited method, so the temporary own property goes.
      if (ownProperty) document.addEventListener = add
      else delete (document as Partial<Document>).addEventListener
      for (const name of ['onblur', 'onfocus'] as const) {
        if (window[name] !== before[name]) this.windowHandlers.push({ name, before: before[name], assigned: window[name] })
      }
    }
    // Destroyed before it ever started: Phaser still starts the loop to run the pending destroy.
    if (this.released) this.removeGlobalHandlers()
  }

  removeGlobalHandlers(): void {
    this.released = true
    for (const [type, listener, options] of this.addedToDocument.splice(0)) {
      document.removeEventListener(type, listener, options)
    }
    for (const { name, before, assigned } of this.windowHandlers.splice(0)) {
      if (window[name] === assigned) window[name] = before
    }
  }
}

/**
 * Creates the Phaser game that draws the wheel inside `parent`. This module is the only one that
 * imports Phaser, and the React component loads it with a dynamic `import()`, so Phaser is a separate
 * chunk that only the roulette page downloads.
 *
 * Throws `WheelUnavailableError` when the browser has no canvas. Phaser boots synchronously in an
 * already loaded page, so a renderer that cannot be created also throws here.
 */
export function createWheelGame(parent: HTMLElement, { reducedMotion }: { reducedMotion: boolean }): WheelGame {
  if (!canRenderWheel(parent.ownerDocument)) throw new WheelUnavailableError()

  let markReady: () => void = () => undefined
  const ready = new Promise<void>((resolve) => {
    markReady = resolve
  })
  let markGone: () => void = () => undefined
  const gone = new Promise<void>((resolve) => {
    markGone = resolve
  })
  const scene = new RouletteScene({ reducedMotion, onReady: () => markReady() })
  const config: Types.Core.GameConfig = {
    type: AUTO, // WebGL when available, otherwise the Canvas renderer
    parent,
    transparent: true,
    banner: false,
    autoFocus: false, // never take the keyboard focus from the page
    // Purely visual. Each manager is switched off explicitly: Phaser reads `input.mouse` etc. with a
    // default of `true`, so `input: false` left mouse, touch and keyboard on, and the mouse manager
    // blocked page scrolling over the canvas (preventDefault on wheel), measured in Chrome.
    input: { keyboard: false, mouse: false, touch: false, gamepad: false, windowEvents: false },
    disableContextMenu: false,
    audio: { noAudio: true },
    scale: {
      mode: Scale.RESIZE, // the canvas follows the size of `parent`
      width: parent.clientWidth || 320,
      height: parent.clientHeight || 320,
    },
    scene,
  }
  const game = new WheelPhaserGame(config)

  let destroyed = false
  const notShown = () => new Error('The wheel is not shown.')
  return {
    ready,
    spinTo: async (winningNumber) => {
      pocketIndex(winningNumber) // RangeError for anything but 0–36
      if (destroyed) throw notShown()
      await Promise.race([ready, gone])
      if (destroyed) throw notShown()
      await scene.spinTo(winningNumber)
    },
    // Also before the scene is drawn: the scene keeps the value for its first spin.
    setReducedMotion: (value) => {
      if (!destroyed) scene.setReducedMotion(value)
    },
    destroy: () => {
      if (destroyed) return
      destroyed = true
      markGone()
      game.removeGlobalHandlers()
      // Deferred by Phaser to its next frame: scenes (DESTROY → a running spin settles), renderer,
      // canvas (removed from `parent`) and loop.
      game.destroy(true)
    },
  }
}
