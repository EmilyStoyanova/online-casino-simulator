import { useEffect, useId, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { canRenderWheel } from '../../games/roulette/canvasSupport'
import type { WheelGame } from '../../games/roulette/createWheelGame'
import { EUROPEAN_WHEEL_ORDER, numberColor } from './rouletteNumbers'
import { usePrefersReducedMotion } from './usePrefersReducedMotion'

/** What a later task (W5-07) may do with the wheel: show the number the server returned. */
export interface RouletteWheelHandle {
  /** Turns the wheel to the server's `winningNumber`; rejects while the wheel is not shown. */
  spinTo: (winningNumber: number) => Promise<void>
}

type Status = 'loading' | 'ready' | 'unavailable'

type WheelModule = typeof import('../../games/roulette/createWheelGame')
let wheelModule: Promise<WheelModule> | null = null

/**
 * Loads the Phaser chunk once for the whole page (StrictMode's trial mount and the real mount share
 * one request). A failed load is forgotten, so a later mount can try again.
 */
function loadWheelModule(): Promise<WheelModule> {
  wheelModule ??= import('../../games/roulette/createWheelGame').catch((error: unknown) => {
    wheelModule = null
    throw error
  })
  return wheelModule
}

const colorName = { RED: 'red', BLACK: 'black', GREEN: 'green' } as const

const pocketStyle = {
  RED: 'bg-rose-700 text-white',
  BLACK: 'bg-zinc-900 text-white ring-1 ring-zinc-700',
  GREEN: 'bg-emerald-700 text-white',
} as const

/**
 * The European roulette wheel, drawn by Phaser (W5-P01). React owns the page, the data and the API
 * calls; Phaser only draws the wheel and, when asked through the ref, animates it to a number the
 * server returned. The canvas is decorative (`aria-hidden`); the same information is in the HTML
 * below it.
 *
 * Phaser is loaded with a dynamic `import()` when this component mounts, so other pages never download
 * it. Exactly one game exists per mounted component, also under StrictMode's trial mount, and it is
 * destroyed (canvas removed) on unmount. Without canvas support, or if the chunk cannot be loaded, the
 * wheel is replaced by a short notice and the description stays.
 */
export function RouletteWheel({ ref }: { ref?: Ref<RouletteWheelHandle> }) {
  const container = useRef<HTMLDivElement>(null)
  const game = useRef<WheelGame | null>(null)
  const reducedMotion = usePrefersReducedMotion()
  const reducedMotionNow = useRef(reducedMotion)
  const [status, setStatus] = useState<Status>('loading')
  const captionId = useId()

  // Declared before the game effect, so the game starts with the current preference.
  useEffect(() => {
    reducedMotionNow.current = reducedMotion
    game.current?.setReducedMotion(reducedMotion)
  }, [reducedMotion])

  useEffect(() => {
    const parent = container.current
    if (parent === null) return undefined
    let mounted = true

    // Without canvas the Phaser chunk is not even downloaded (its feature detection would fail).
    const loaded = canRenderWheel(parent.ownerDocument)
      ? loadWheelModule()
      : Promise.reject(new Error('This browser cannot draw the roulette wheel.'))
    loaded
      .then(({ createWheelGame }) => {
        if (!mounted) return undefined // StrictMode's trial mount, or left before the chunk arrived
        const created = createWheelGame(parent, { reducedMotion: reducedMotionNow.current })
        game.current = created
        return created.ready.then(() => {
          if (mounted) setStatus('ready')
        })
      })
      .catch(() => {
        if (mounted) setStatus('unavailable')
      })

    return () => {
      mounted = false
      game.current?.destroy()
      game.current = null
    }
  }, [])

  useImperativeHandle(
    ref,
    () => ({
      spinTo: (winningNumber) =>
        game.current ? game.current.spinTo(winningNumber) : Promise.reject(new Error('The wheel is not shown.')),
    }),
    [],
  )

  return (
    <figure aria-labelledby={captionId} className="flex flex-col items-center gap-4">
      <div className="relative aspect-square w-full max-w-sm">
        {/* Phaser owns this element's children (its canvas); React renders nothing inside it. */}
        <div ref={container} aria-hidden="true" data-testid="roulette-wheel-canvas" className="absolute inset-0" />
        <div aria-live="polite" className="pointer-events-none absolute inset-0 grid place-items-center p-6 text-center">
          {status === 'loading' && <p className="text-sm text-zinc-400">Loading the wheel…</p>}
          {status === 'unavailable' && (
            <p className="text-sm leading-relaxed text-zinc-300">
              The wheel picture cannot be shown in this browser. The pocket order is listed below.
            </p>
          )}
        </div>
      </div>
      <figcaption id={captionId} className="text-center text-sm text-zinc-300">
        European single-zero wheel: 37 pockets, 0 to 36. Zero is green; the other numbers alternate red and black.
      </figcaption>
      <details className="w-full max-w-sm text-sm text-zinc-300">
        <summary className="cursor-pointer rounded-md text-zinc-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400">
          Pocket order on the wheel
        </summary>
        <ol aria-label="Pockets clockwise from zero" className="mt-3 grid grid-cols-6 gap-1.5 sm:grid-cols-8">
          {EUROPEAN_WHEEL_ORDER.map((value) => {
            const color = numberColor(value)
            return (
              <li key={value} className={`rounded-md py-1 text-center text-xs font-semibold tabular-nums ${pocketStyle[color]}`}>
                {value}
                <span className="sr-only"> {colorName[color]}</span>
              </li>
            )
          })}
        </ol>
      </details>
    </figure>
  )
}
