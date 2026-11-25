import { useId } from 'react'
import {
  DOZEN_CELLS,
  NUMBER_CELLS,
  OUTSIDE_CELLS,
  cellAccessibleName,
  formatAmount,
  type BetCell,
} from './rouletteTable'

const cellBase =
  'relative flex min-h-11 min-w-0 flex-col items-center justify-center rounded-md px-1 py-1 text-white transition max-md:scroll-mb-24 focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-300 aria-disabled:cursor-not-allowed aria-disabled:opacity-60'

/** White text on every background has at least 4.5:1 contrast. */
const cellColor = {
  RED: 'bg-rose-700 hover:bg-rose-600',
  BLACK: 'bg-zinc-900 ring-1 ring-inset ring-zinc-600 hover:bg-zinc-800',
  GREEN: 'bg-emerald-700 hover:bg-emerald-600',
  none: 'bg-emerald-900 ring-1 ring-inset ring-emerald-600/60 hover:bg-emerald-800',
} as const

/** The twelve rows of three numbers: 1 2 3, 4 5 6, … 34 35 36 (zero is separate). */
const NUMBER_TRIPLES: readonly (readonly BetCell[])[] = Array.from({ length: 12 }, (_, row) =>
  NUMBER_CELLS.slice(1 + row * 3, 4 + row * 3),
)

interface BoardProps {
  /** The amount placed on each selection, by `BetCell.key`. */
  amounts: ReadonlyMap<string, number>
  onPlace: (cell: BetCell) => void
  /** While set, every cell is `aria-disabled` and explains why; a click changes nothing. */
  lockedReason: string | null
}

function Cell({
  entry,
  amount,
  onPlace,
  lockId,
  className = '',
}: {
  entry: BetCell
  amount: number
  onPlace: (cell: BetCell) => void
  lockId: string | undefined
  className?: string
}) {
  return (
    <button
      type="button"
      aria-label={cellAccessibleName(entry, amount)}
      aria-disabled={lockId ? true : undefined}
      aria-describedby={lockId}
      data-color={entry.color ?? 'NONE'}
      onClick={() => {
        if (!lockId) onPlace(entry)
      }}
      className={`${cellBase} ${cellColor[entry.color ?? 'none']} ${className}`}
    >
      <span className="text-sm font-semibold leading-5 tabular-nums">{entry.label}</span>
      {entry.detail && <span className="text-[11px] leading-4 text-white/80">{entry.detail}</span>}
      {amount > 0 && (
        <span className="mt-0.5 rounded-full bg-amber-300 px-1.5 text-[10px] font-bold leading-4 text-zinc-950 tabular-nums shadow">
          {formatAmount(amount)}
        </span>
      )}
    </button>
  )
}

/**
 * The betting table (§5.3): native buttons in three labelled groups, "Numbers", "Dozens" and "Outside
 * bets". The DOM order is 0, 1–36 ascending, the dozens, then the outside bets, in every layout:
 *
 * - below `md` the table is vertical: zero on top, then twelve rows of three (1 2 3 … 34 35 36);
 * - from `md` it is horizontal as on a real table: zero on the left, then twelve columns, each a row
 *   of three turned with `flex-col-reverse` (1 at the bottom, 3 on top), so only the look changes.
 *
 * A placed bet shows its amount on the cell as text, and the cell's name ends with "… coins placed."
 */
export function RouletteBoard({ amounts, onPlace, lockedReason }: BoardProps) {
  const numbersId = useId()
  const dozensId = useId()
  const outsideId = useId()
  const lockId = useId()
  const locked = lockedReason ? lockId : undefined
  const render = (entry: BetCell, className?: string) => (
    <Cell
      key={entry.key}
      entry={entry}
      amount={amounts.get(entry.key) ?? 0}
      onPlace={onPlace}
      lockId={locked}
      className={className}
    />
  )

  return (
    <div className="space-y-4">
      {lockedReason && (
        <p id={lockId} className="rounded-lg border border-zinc-700 bg-zinc-800/60 px-3 py-2 text-sm text-zinc-200">
          {lockedReason}
        </p>
      )}
      <div className="space-y-4 rounded-xl bg-emerald-950 p-2 ring-1 ring-emerald-800/70 sm:p-3">
        <div role="group" aria-labelledby={numbersId}>
          <p className="mb-2 text-xs text-emerald-100/80">
            <span id={numbersId} className="font-semibold text-emerald-50">
              Numbers
            </span>{' '}
            · straight bet, pays 35 to 1
          </p>
          <div className="flex flex-col gap-1 md:flex-row">
            {render(NUMBER_CELLS[0] as BetCell, 'md:w-12 md:shrink-0')}
            <div className="flex flex-col gap-1 md:flex-1 md:flex-row">
              {NUMBER_TRIPLES.map((triple) => (
                <div key={triple[0]?.key} className="grid grid-cols-3 gap-1 md:flex md:min-w-0 md:flex-1 md:flex-col-reverse">
                  {triple.map((entry) => render(entry))}
                </div>
              ))}
            </div>
          </div>
        </div>

        <div role="group" aria-labelledby={dozensId}>
          <p className="mb-2 text-xs text-emerald-100/80">
            <span id={dozensId} className="font-semibold text-emerald-50">
              Dozens
            </span>{' '}
            · pay 2 to 1
          </p>
          <div className="grid grid-cols-3 gap-1 md:ml-13">{DOZEN_CELLS.map((entry) => render(entry))}</div>
        </div>

        <div role="group" aria-labelledby={outsideId}>
          <p className="mb-2 text-xs text-emerald-100/80">
            <span id={outsideId} className="font-semibold text-emerald-50">
              Outside bets
            </span>{' '}
            · pay 1 to 1
          </p>
          <div className="grid grid-cols-3 gap-1 md:ml-13 md:grid-cols-6">{OUTSIDE_CELLS.map((entry) => render(entry))}</div>
        </div>
      </div>
    </div>
  )
}
