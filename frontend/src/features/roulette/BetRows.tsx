import { Coins } from '../../components/Coins'
import type { Message } from '../../i18n/translate'
import { useI18n } from '../../i18n/useI18n'
import { ROULETTE_LIMITS } from '../../types/roulette'
import { totalStake, type SlipBet } from './betSlipReducer'
import { cellFor, cellText } from './rouletteTable'

/**
 * The bets of the slip, of the confirmation or of an unresolved attempt: name, bet type and odds,
 * amount, and (only while editing) a remove button. A server validation message is shown under its row.
 */
export function BetRows({
  bets,
  onRemove,
  errors,
}: {
  bets: readonly SlipBet[]
  onRemove?: (key: string) => void
  errors?: ReadonlyMap<string, readonly Message[]>
}) {
  const { t, locale, coins } = useI18n()
  return (
    <ul aria-label={t('slip.yourBets')} className="divide-y divide-zinc-800 rounded-lg border border-zinc-800">
      {bets.map((bet) => {
        const text = cellText(cellFor(bet.selection), locale)
        const messages = (errors?.get(bet.key) ?? []).map((message) => t(message))
        return (
          <li key={bet.key} className="px-3 py-2">
            <div className="flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium break-words text-zinc-100">{text.name}</p>
                <p className="text-xs text-zinc-400">
                  {text.betType} · <span className="whitespace-nowrap">{t('slip.pays', { odds: text.odds })}</span>
                </p>
              </div>
              <Coins amount={bet.amount} className="shrink-0 text-sm font-semibold text-zinc-100" />
              {onRemove && (
                <button
                  type="button"
                  onClick={() => onRemove(bet.key)}
                  data-bet-key={bet.key}
                  aria-label={t('slip.remove', { name: text.name, amount: coins(bet.amount) })}
                  className="-mr-1 flex size-11 shrink-0 items-center justify-center rounded-lg text-zinc-400 transition hover:bg-zinc-800 hover:text-zinc-100 max-md:scroll-mb-24 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400"
                >
                  <span aria-hidden="true" className="text-lg leading-none">
                    ✕
                  </span>
                </button>
              )}
            </div>
            {messages.map((message) => (
              <p key={message} className="mt-1 text-xs text-rose-300">
                {message}
              </p>
            ))}
          </li>
        )
      })}
    </ul>
  )
}

/** "Bets n of 10" and "Total stake": the only sum the client computes. */
export function Totals({ bets }: { bets: readonly SlipBet[] }) {
  const { t } = useI18n()
  return (
    <dl className="grid grid-cols-2 gap-y-1 text-sm">
      <dt className="text-zinc-400">{t('slip.bets')}</dt>
      <dd className="text-right tabular-nums text-zinc-200">
        {t('slip.betsCount', { count: bets.length, max: ROULETTE_LIMITS.maxBets })}
      </dd>
      <dt className="text-zinc-400">{t('slip.totalStake')}</dt>
      <dd className="text-right font-semibold text-zinc-50">
        <Coins amount={totalStake(bets)} />
      </dd>
    </dl>
  )
}
