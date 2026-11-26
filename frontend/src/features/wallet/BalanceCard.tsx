import { useId } from 'react'
import { secondaryButton } from '../../components/buttonStyles'
import { card } from '../../components/cardStyles'
import { Coins } from '../../components/Coins'
import { FormAlert } from '../../components/form/FormAlert'
import { useI18n } from '../../i18n/useI18n'
import { useWallet } from './useWallet'

const compact = 'px-3 py-1.5'

/**
 * The player's balance as a card (§6.1): the lobby, the roulette page and the wallet page. A
 * placeholder while the first read is in flight (never a number), "Balance unavailable" with a retry
 * after a failed read, and the last confirmed value during a refresh. A failed refresh keeps that
 * value but says that it may be out of date. Renders nothing while the wallet is inactive.
 */
export function BalanceCard() {
  const { state, refresh } = useWallet()
  const { t, dateTime } = useI18n()
  const headingId = useId()

  if (state.status === 'inactive') return null
  const busy = state.status === 'loading' || (state.status === 'loaded' && state.refresh === 'refreshing')

  return (
    <section aria-labelledby={headingId} aria-busy={busy} className={`${card} flex flex-col`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-zinc-100">
          {t('balance.heading')}
        </h2>
        {state.status === 'loaded' && (
          <button
            type="button"
            onClick={refresh}
            disabled={state.refresh === 'refreshing'}
            className={`${secondaryButton} ${compact}`}
          >
            {state.refresh === 'refreshing' ? t('common.refreshing') : t('common.refresh')}
          </button>
        )}
      </div>

      {state.status === 'loading' && (
        <div className="mt-3 space-y-2">
          <div aria-hidden="true" className="h-9 w-40 animate-pulse rounded-lg bg-zinc-800" />
          <p className="text-sm text-zinc-400">{t('balance.loading')}</p>
        </div>
      )}

      {state.status === 'failed' && (
        <div className="mt-3 space-y-3">
          <p className="text-2xl font-semibold text-zinc-100">{t('balance.unavailable')}</p>
          <p className="text-sm text-zinc-400">{t(state.message)}</p>
          <div>
            <button type="button" onClick={refresh} className={`${secondaryButton} ${compact}`}>
              {t('common.tryAgain')}
            </button>
          </div>
        </div>
      )}

      {state.status === 'loaded' && (
        <div className="mt-3 space-y-2">
          <p className="text-3xl font-bold tracking-tight text-zinc-50">
            <Coins amount={state.balance} />
          </p>
          <p className="text-xs text-zinc-400">
            {state.updatedAt === null ? t('balance.fromRound') : t('balance.updated', { time: dateTime(state.updatedAt) })}
          </p>
          {state.refresh === 'failed' && (
            <FormAlert tone="error">
              {t('balance.refreshFailed')}{' '}
              <button type="button" onClick={refresh} className="font-semibold underline underline-offset-2">
                {t('common.tryAgain')}
              </button>
            </FormAlert>
          )}
        </div>
      )}

      <p className="mt-4 text-xs text-zinc-400">{t('balance.noMonetaryValue')}</p>
    </section>
  )
}
