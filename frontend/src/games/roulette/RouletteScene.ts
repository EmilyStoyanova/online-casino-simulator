import { GameObjects, Scale, Scene, Scenes, Structs } from 'phaser'
import {
  NEUTRAL_ROTATION,
  POCKET_ANGLE,
  pockets,
  restingRotation,
  SPIN_DURATION_MS,
  spinTargetRotation,
  WHEEL_COLORS,
  WHEEL_RADII,
} from './wheelConfig'

/** The wheel is drawn once at this radius and scaled to the canvas. */
const BASE_RADIUS = 240
/** Share of the smaller canvas side used by the wheel; the rest leaves room for the pointer. */
const FILL = 0.9
const NUMERAL_FONT = 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif'

export interface RouletteSceneOptions {
  reducedMotion: boolean
  /** Called once the wheel is drawn. */
  onReady: () => void
}

/**
 * Draws a European single-zero wheel and, when asked, turns it to a number the server returned.
 * It has no input, no timers and no random source: without a `spinTo` call the wheel stands still.
 */
export class RouletteScene extends Scene {
  private wheel!: GameObjects.Container
  private pointer!: GameObjects.Graphics
  private reducedMotion: boolean
  private readonly onReady: () => void
  /** The rotation the wheel is heading to (or resting at). */
  private target = NEUTRAL_ROTATION
  /** Resolves the promise of the spin in progress. */
  private settle: (() => void) | null = null

  constructor({ reducedMotion, onReady }: RouletteSceneOptions) {
    super({ key: 'roulette-wheel' })
    this.reducedMotion = reducedMotion
    this.onReady = onReady
  }

  create(): void {
    // Before any server result the pointer marks no number (NEUTRAL_ROTATION).
    this.wheel = this.add.container(0, 0, this.drawWheel()).setRotation(NEUTRAL_ROTATION)
    this.pointer = this.drawPointer()
    this.layout(this.scale.gameSize)

    this.scale.on(Scale.Events.RESIZE, this.layout, this)
    // `Game.destroy()` destroys the scene (DESTROY, no SHUTDOWN); stopping a scene shuts it down. Either
    // way the resize listener goes and a running spin settles. The TweenManager removes its own tweens.
    const release = () => {
      this.scale?.off(Scale.Events.RESIZE, this.layout, this)
      this.finishSpin()
    }
    this.events.once(Scenes.Events.SHUTDOWN, release)
    this.events.once(Scenes.Events.DESTROY, release)
    this.onReady()
  }

  /**
   * Turns the wheel so that the pocket of `winningNumber` stops under the pointer: a few full turns
   * with an ease-out, or at once with reduced motion. The number must come from the server.
   */
  spinTo(winningNumber: number): Promise<void> {
    const reduced = this.reducedMotion
    this.target = reduced ? restingRotation(winningNumber) : spinTargetRotation(this.wheel.rotation, winningNumber)
    this.tweens.killTweensOf(this.wheel)
    this.finishSpin()

    if (reduced) {
      this.wheel.setRotation(this.target)
      return Promise.resolve()
    }
    return new Promise<void>((resolve) => {
      this.settle = resolve
      this.tweens.add({
        targets: this.wheel,
        rotation: this.target,
        duration: SPIN_DURATION_MS,
        ease: 'Cubic.easeOut',
        onComplete: () => this.finishSpin(),
      })
    })
  }

  /** Switching reduced motion on during a spin ends it at once, on the same pocket. */
  setReducedMotion(reducedMotion: boolean): void {
    this.reducedMotion = reducedMotion
    if (reducedMotion && this.settle) {
      this.tweens.killTweensOf(this.wheel)
      this.wheel.setRotation(this.target)
      this.finishSpin()
    }
  }

  private finishSpin(): void {
    const settle = this.settle
    this.settle = null
    settle?.()
  }

  private layout(gameSize: Structs.Size): void {
    const { width, height } = gameSize
    const scale = (Math.min(width, height) * FILL) / 2 / BASE_RADIUS
    for (const object of [this.wheel, this.pointer]) {
      object.setPosition(width / 2, height / 2)
      object.setScale(scale)
    }
  }

  private drawWheel(): GameObjects.GameObject[] {
    const r = BASE_RADIUS
    const g = this.add.graphics()

    // Rim with a thin accent ring.
    g.fillStyle(WHEEL_COLORS.rim).fillCircle(0, 0, r * WHEEL_RADII.rim)
    g.lineStyle(3, WHEEL_COLORS.rimEdge).strokeCircle(0, 0, r * WHEEL_RADII.rim - 1.5)

    // The 37 pockets; Phaser measures angles from the positive x axis, the wheel from the top.
    for (const { centre, color } of pockets()) {
      const start = centre - POCKET_ANGLE / 2 - Math.PI / 2
      g.fillStyle(WHEEL_COLORS.pocket[color])
      g.beginPath()
      g.slice(0, 0, r * WHEEL_RADII.pocketsOuter, start, start + POCKET_ANGLE, false)
      g.fillPath()
    }

    // Inner cone, separators between the pockets, accent rings around the pocket track.
    g.fillStyle(WHEEL_COLORS.rim).fillCircle(0, 0, r * WHEEL_RADII.pocketsInner)
    g.lineStyle(1.5, WHEEL_COLORS.separator, 0.55)
    for (const { centre } of pockets()) {
      const angle = centre - POCKET_ANGLE / 2 - Math.PI / 2
      g.lineBetween(
        Math.cos(angle) * r * WHEEL_RADII.pocketsInner,
        Math.sin(angle) * r * WHEEL_RADII.pocketsInner,
        Math.cos(angle) * r * WHEEL_RADII.pocketsOuter,
        Math.sin(angle) * r * WHEEL_RADII.pocketsOuter,
      )
    }
    g.lineStyle(2, WHEEL_COLORS.accent, 0.7)
    g.strokeCircle(0, 0, r * WHEEL_RADII.pocketsOuter)
    g.strokeCircle(0, 0, r * WHEEL_RADII.pocketsInner)

    // Hub with four spokes and a centre cap.
    g.fillStyle(WHEEL_COLORS.hub).fillCircle(0, 0, r * WHEEL_RADII.hub)
    g.lineStyle(3, WHEEL_COLORS.hubRing, 0.7).strokeCircle(0, 0, r * WHEEL_RADII.hub)
    g.lineStyle(6, WHEEL_COLORS.rimEdge)
    for (let spoke = 0; spoke < 4; spoke += 1) {
      const angle = (spoke * Math.PI) / 2 + Math.PI / 4
      g.lineBetween(0, 0, Math.cos(angle) * r * WHEEL_RADII.hub * 0.85, Math.sin(angle) * r * WHEEL_RADII.hub * 0.85)
    }
    g.fillStyle(WHEEL_COLORS.accent).fillCircle(0, 0, r * WHEEL_RADII.cap)

    // Numerals, upright towards the rim.
    const numerals = pockets().map(({ value, centre }) =>
      this.add
        .text(Math.sin(centre) * r * WHEEL_RADII.numerals, -Math.cos(centre) * r * WHEEL_RADII.numerals, String(value), {
          fontFamily: NUMERAL_FONT,
          fontSize: `${Math.round(r * 0.08)}px`,
          fontStyle: 'bold',
          color: WHEEL_COLORS.numeral,
          resolution: Math.min(2, Math.max(1, window.devicePixelRatio || 1)),
        })
        .setOrigin(0.5)
        .setRotation(centre),
    )
    return [g, ...numerals]
  }

  /** The fixed pointer at the top, pointing down into the pocket track. */
  private drawPointer(): GameObjects.Graphics {
    const r = BASE_RADIUS
    const tip = -r * (WHEEL_RADII.pocketsOuter - 0.08)
    const base = -r * (WHEEL_RADII.rim + 0.04)
    return this.add
      .graphics()
      .fillStyle(WHEEL_COLORS.accent)
      .fillTriangle(-14, base, 14, base, 0, tip)
      .lineStyle(2, WHEEL_COLORS.hub, 0.8)
      .strokeTriangle(-14, base, 14, base, 0, tip)
  }
}
