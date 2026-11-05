import { CheckboxField } from '../../components/form/CheckboxField'
import { FormAlert } from '../../components/form/FormAlert'
import { secondaryButton } from '../../components/buttonStyles'
import { MAX_INTERESTS } from './profileChanges'
import type { InterestCatalog } from './useInterests'

export interface InterestPickerProps {
  id: string
  catalog: InterestCatalog
  onRetryCatalog: () => void
  /** Selected codes; changed only through `onChange`, saved only by the surrounding form. */
  selected: readonly string[]
  onChange: (codes: string[]) => void
  disabled?: boolean
  error?: string
}

/**
 * Chooses 0–{@link MAX_INTERESTS} interests from the catalogue, one checkbox each. Once the maximum is
 * reached, the unselected ones are disabled. If the catalogue cannot be loaded, the selection is kept
 * unchanged and shown by code, so a save cannot remove it by accident.
 */
export function InterestPicker({ id, catalog, onRetryCatalog, selected, onChange, disabled, error }: InterestPickerProps) {
  const hintId = `${id}-hint`
  const errorId = error ? `${id}-error` : undefined
  const atMaximum = selected.length >= MAX_INTERESTS

  function toggle(code: string, checked: boolean) {
    if (catalog.status !== 'loaded') return
    const chosen = new Set(selected)
    if (checked) {
      if (atMaximum) return
      chosen.add(code)
    } else {
      chosen.delete(code)
    }
    // Catalogue order, as the server returns it; codes outside the catalogue keep their place at the end.
    const known = catalog.interests.map((interest) => interest.code)
    onChange([...known.filter((c) => chosen.has(c)), ...selected.filter((c) => chosen.has(c) && !known.includes(c))])
  }

  return (
    <fieldset aria-describedby={[hintId, errorId].filter(Boolean).join(' ')} className="space-y-3">
      <legend className="text-sm font-medium text-zinc-200">Interests</legend>
      <div id={hintId} className="space-y-1 text-xs text-zinc-500">
        <p>
          Choose up to {MAX_INTERESTS} topics you would like to learn about. Your choice is saved when you
          save your profile.
        </p>
        <p aria-live="polite" className={atMaximum ? 'text-amber-300' : undefined}>
          {selected.length} of {MAX_INTERESTS} selected.
          {atMaximum && ` You have reached the maximum; deselect one to choose another.`}
        </p>
      </div>

      {catalog.status === 'loading' && (
        <p role="status" className="flex items-center gap-2 text-sm text-zinc-400">
          <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-emerald-400/70" />
          Loading interests…
        </p>
      )}

      {catalog.status === 'failed' && (
        <div className="space-y-3">
          <FormAlert tone="error">
            {catalog.message} Your current selection is kept as it is
            {selected.length > 0 ? `: ${selected.join(', ')}.` : '.'}
          </FormAlert>
          <button type="button" onClick={onRetryCatalog} className={secondaryButton}>
            Load interests again
          </button>
        </div>
      )}

      {catalog.status === 'loaded' && catalog.interests.length === 0 && (
        <p className="text-sm text-zinc-400">No interests are available at the moment.</p>
      )}

      {catalog.status === 'loaded' && catalog.interests.length > 0 && (
        <ul className="grid gap-2 sm:grid-cols-2">
          {catalog.interests.map((interest) => {
            const checked = selected.includes(interest.code)
            return (
              <li
                key={interest.code}
                className="rounded-lg border border-zinc-800 bg-zinc-950/40 p-3 transition has-checked:border-emerald-500/60 has-checked:bg-emerald-500/10"
              >
                <CheckboxField
                  id={`${id}-${interest.code}`}
                  label={
                    <>
                      <span className="block font-medium text-zinc-100">{interest.name}</span>
                      <span className="block text-xs text-zinc-400">{interest.description}</span>
                    </>
                  }
                  checked={checked}
                  onChange={(event) => toggle(interest.code, event.target.checked)}
                  disabled={disabled || (!checked && atMaximum)}
                  aria-invalid={error ? true : undefined}
                />
              </li>
            )
          })}
        </ul>
      )}

      {error && (
        <p id={errorId} className="text-xs font-medium text-rose-300">
          {error}
        </p>
      )}
    </fieldset>
  )
}
