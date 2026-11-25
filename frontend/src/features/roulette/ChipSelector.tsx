import { useId } from 'react'
import { formatCoins } from './rouletteTable'
import { CHIP_VALUES, type ChipValue } from './betSlipReducer'

/** Each chip has its own colour **and** its numeral, so the value never depends on colour. */
const chipStyle: Record<ChipValue, string> = {
  1: 'bg-zinc-100 text-zinc-900 border-zinc-400',
  5: 'bg-sky-700 text-white border-sky-300/60',
  10: 'bg-violet-700 text-white border-violet-300/60',
  25: 'bg-teal-700 text-white border-teal-300/60',
  100: 'bg-amber-400 text-zinc-950 border-amber-100/80',
  500: 'bg-fuchsia-700 text-white border-fuchsia-300/60',
}

/**
 * The chip value (§5.4): a native radio group in a `fieldset`, so Tab reaches the group, the arrow keys
 * move the selection and screen readers announce "10 coins, radio button, checked". The chosen chip
 * has a ring (a change of shape, not only of colour) and the hint below names its value in text.
 */
export function ChipSelector({ value, onChange }: { value: ChipValue; onChange: (value: ChipValue) => void }) {
  const name = useId()
  const hintId = useId()

  return (
    <fieldset aria-describedby={hintId}>
      <legend className="text-sm font-semibold text-zinc-100">Chip value</legend>
      <div className="mt-3 flex flex-wrap gap-2.5">
        {CHIP_VALUES.map((chip) => (
          <label key={chip} className="relative cursor-pointer">
            <input
              type="radio"
              name={name}
              value={chip}
              checked={chip === value}
              onChange={() => onChange(chip)}
              className="peer sr-only"
            />
            <span
              className={`flex size-11 items-center justify-center rounded-full border-2 border-dashed text-sm font-bold tabular-nums shadow-md shadow-black/40 transition peer-checked:scale-110 peer-checked:ring-2 peer-checked:ring-white peer-checked:ring-offset-2 peer-checked:ring-offset-zinc-900 peer-focus-visible:outline-2 peer-focus-visible:outline-offset-4 peer-focus-visible:outline-emerald-400 ${chipStyle[chip]}`}
            >
              <span aria-hidden="true">{chip}</span>
              <span className="sr-only">{formatCoins(chip)}</span>
            </span>
          </label>
        ))}
      </div>
      <p id={hintId} className="mt-3 text-xs text-zinc-400">
        Each choice on the table adds <span className="font-semibold text-zinc-200">{formatCoins(value)}</span>.
      </p>
    </fieldset>
  )
}
