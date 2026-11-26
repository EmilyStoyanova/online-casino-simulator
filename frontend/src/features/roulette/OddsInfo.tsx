import { useI18n } from '../../i18n/useI18n'
import { CATEGORY_RULES, chanceText, houseEdgePercent, POCKETS, returnToPlayerPercent } from './oddsRules'

/**
 * "Odds and rules" (§5.8, Q8, AC-W5-08.5): a native `<details>` disclosure (keyboard and screen-reader
 * operable without code) with the five categories, what each covers, the chance of a win, the odds,
 * what a win returns, the role of zero and the house edge of 1/37. Static text from the contract; it
 * explains the rules and computes nothing for the player's bets.
 */
export function OddsInfo() {
  const { t, locale } = useI18n()
  return (
    <details className="group rounded-xl border border-zinc-800 bg-zinc-950/40 px-4">
      <summary className="flex min-h-11 cursor-pointer items-center justify-between gap-2 rounded-md text-sm font-semibold text-zinc-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400">
        {t('odds.summary')}
        <span aria-hidden="true" className="text-zinc-400 transition group-open:rotate-180">
          ▾
        </span>
      </summary>
      <div className="space-y-4 pb-4 pt-1 text-sm leading-relaxed text-zinc-300">
        <p>{t('odds.intro', { pockets: POCKETS })}</p>
        <ul aria-label={t('odds.betTypes')} className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {CATEGORY_RULES.map((rule) => (
            <li key={rule.category} className="rounded-lg border border-zinc-800 px-3 py-2">
              <h3 className="font-semibold text-zinc-100">{t(rule.nameKey)}</h3>
              <dl className="mt-1 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs">
                <dt className="text-zinc-400">{t('odds.covers')}</dt>
                <dd className="text-zinc-200">{t(rule.coversKey)}</dd>
                <dt className="text-zinc-400">{t('odds.chance')}</dt>
                <dd className="text-zinc-200">{chanceText(rule.winningNumbers, locale)}</dd>
                <dt className="text-zinc-400">{t('odds.odds')}</dt>
                <dd className="text-zinc-200">{t('odds.toOne', { odds: rule.netOdds })}</dd>
                <dt className="text-zinc-400">{t('odds.returns')}</dt>
                <dd className="text-zinc-200">{t('odds.returnValue', { times: rule.returnedPerCoin })}</dd>
              </dl>
            </li>
          ))}
        </ul>
        <div>
          <h3 className="font-semibold text-zinc-100">{t('odds.zeroHeading')}</h3>
          <p>{t('odds.zeroText')}</p>
        </div>
        <div>
          <h3 className="font-semibold text-zinc-100">{t('odds.edgeHeading')}</h3>
          <p>
            {t('odds.edgeText', {
              edge: houseEdgePercent(locale),
              back: POCKETS - 1,
              pockets: POCKETS,
              rtp: returnToPlayerPercent(locale),
            })}
          </p>
        </div>
        <p>{t('odds.independent')}</p>
      </div>
    </details>
  )
}
