import { useI18n } from '../../i18n/useI18n'
import type { Locale } from '../../i18n/locales'

/** Each language by its own name, in its own language (never translated). */
const OPTIONS: readonly { locale: Locale; short: string; name: string }[] = [
  { locale: 'en', short: 'EN', name: 'English' },
  { locale: 'bg', short: 'BG', name: 'Български' },
]

const option =
  'inline-flex min-h-8 min-w-10 items-center justify-center rounded-md px-2.5 text-xs font-semibold text-zinc-300 transition hover:bg-zinc-800 hover:text-zinc-100 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 max-md:min-h-11 max-md:min-w-11 ' +
  'aria-pressed:bg-emerald-500/15 aria-pressed:text-emerald-200 aria-pressed:ring-1 aria-pressed:ring-emerald-500/40'

/**
 * EN / BG in the header, for visitors and signed-in users alike: a labelled group of two toggle
 * buttons (`aria-pressed` marks the current language). Each button is named "EN English" /
 * "BG Български" and spoken in its own language (`lang`); the name starts with the visible text, so
 * speech input ("click BG") works (WCAG 2.5.3). Choosing a language changes only the texts
 * (`LocaleProvider`).
 */
export function LanguageSwitcher() {
  const { locale, setLocale, t } = useI18n()

  return (
    <div role="group" aria-label={t('language.label')} className="inline-flex gap-0.5 rounded-lg border border-zinc-700 p-0.5">
      {OPTIONS.map((entry) => (
        <button
          key={entry.locale}
          type="button"
          lang={entry.locale}
          aria-pressed={entry.locale === locale}
          aria-label={`${entry.short} ${entry.name}`}
          onClick={() => setLocale(entry.locale)}
          className={option}
        >
          {entry.short}
        </button>
      ))}
    </div>
  )
}
