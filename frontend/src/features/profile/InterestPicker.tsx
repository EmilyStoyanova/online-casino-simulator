import { CheckboxField } from '../../components/form/CheckboxField'
import { FormAlert } from '../../components/form/FormAlert'
import { secondaryButton } from '../../components/buttonStyles'
import { useI18n } from '../../i18n/useI18n'
import { interestDescription, interestName } from './interestText'
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
  const { t, locale } = useI18n()
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
      <legend className="text-sm font-medium text-zinc-200">{t('profile.interests')}</legend>
      <div id={hintId} className="space-y-1 text-xs text-zinc-400">
        <p>{t('interests.hint', { max: MAX_INTERESTS })}</p>
        <p aria-live="polite" className={atMaximum ? 'text-amber-300' : undefined}>
          {t('interests.count', { count: selected.length, max: MAX_INTERESTS })}
          {atMaximum && ` ${t('interests.atMaximum')}`}
        </p>
      </div>

      {catalog.status === 'loading' && (
        <p role="status" className="flex items-center gap-2 text-sm text-zinc-400">
          <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-emerald-400/70" />
          {t('interests.loading')}
        </p>
      )}

      {catalog.status === 'failed' && (
        <div className="space-y-3">
          <FormAlert tone="error">
            {t(catalog.message)}{' '}
            {selected.length > 0 ? t('interests.keptWith', { codes: selected.join(', ') }) : t('interests.kept')}
          </FormAlert>
          <button type="button" onClick={onRetryCatalog} className={secondaryButton}>
            {t('interests.reload')}
          </button>
        </div>
      )}

      {catalog.status === 'loaded' && catalog.interests.length === 0 && (
        <p className="text-sm text-zinc-400">{t('interests.none')}</p>
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
                      <span className="block font-medium text-zinc-100">{interestName(interest, locale)}</span>
                      <span className="block text-xs text-zinc-400">{interestDescription(interest, locale)}</span>
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
