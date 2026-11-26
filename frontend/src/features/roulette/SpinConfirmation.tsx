import type { Ref } from 'react'
import { primaryButton, secondaryButton, touchTarget } from '../../components/buttonStyles'
import { Coins } from '../../components/Coins'
import { useI18n } from '../../i18n/useI18n'
import type { WalletState } from '../wallet/walletContext'
import { BetRows, Totals } from './BetRows'
import type { SlipBet } from './betSlipReducer'

export function BalanceLine({ wallet }: { wallet: WalletState }) {
  const { t, rich } = useI18n()
  if (wallet.status === 'loaded') {
    return (
      <p className="text-sm text-zinc-300">
        {rich('balance.line', { amount: <Coins amount={wallet.balance} className="font-semibold text-zinc-100" /> })}
        {wallet.refresh === 'failed' && <span className="text-zinc-400"> {t('balance.mayBeStale')}</span>}
      </p>
    )
  }
  if (wallet.status === 'loading') return <p className="text-sm text-zinc-400">{t('balance.checking')}</p>
  return <p className="text-sm text-zinc-400">{t('balance.unknown')}</p>
}

/**
 * The inline confirmation (§5.6, Q4): the exact bets and total stake, the balance, and the deliberate
 * second step "Confirm and spin". While the request is in flight the button says "Waiting for the
 * server…" and stays focusable but inert (`aria-disabled` and a guarded handler), so the focus is not
 * lost; "Back to bets" is inert as well.
 */
export function SpinConfirmation({
  bets,
  wallet,
  reason,
  reasonId,
  submitting,
  headingRef,
  onConfirm,
  onBack,
}: {
  bets: readonly SlipBet[]
  wallet: WalletState
  /** Why the spin cannot be confirmed right now (e.g. the balance fell), or `null`. */
  reason: string | null
  reasonId: string
  submitting: boolean
  headingRef: Ref<HTMLHeadingElement>
  onConfirm: () => void
  onBack: () => void
}) {
  const { t } = useI18n()
  const inert = submitting || reason !== null
  return (
    <div className="space-y-4" aria-busy={submitting}>
      <h3 ref={headingRef} tabIndex={-1} className="text-base font-semibold text-zinc-100 focus:outline-none">
        {t('confirm.heading')}
      </h3>
      <BetRows bets={bets} />
      <Totals bets={bets} />
      <BalanceLine wallet={wallet} />
      <p className="text-sm leading-relaxed text-zinc-300">{t('confirm.note')}</p>
      {reason && (
        <p id={reasonId} className="text-sm text-amber-200">
          {reason}
        </p>
      )}
      <div className="flex flex-col gap-2">
        <button
          type="button"
          onClick={() => {
            if (!inert) onConfirm()
          }}
          aria-disabled={inert ? true : undefined}
          aria-describedby={reason ? reasonId : undefined}
          className={`${primaryButton} w-full max-md:scroll-mb-24 ${touchTarget} aria-disabled:cursor-not-allowed aria-disabled:opacity-60`}
        >
          {submitting ? t('common.waitingForServer') : t('confirm.submit')}
        </button>
        <button
          type="button"
          onClick={() => {
            if (!submitting) onBack()
          }}
          aria-disabled={submitting ? true : undefined}
          className={`${secondaryButton} w-full max-md:scroll-mb-24 ${touchTarget} aria-disabled:cursor-not-allowed aria-disabled:opacity-60`}
        >
          {t('confirm.back')}
        </button>
      </div>
    </div>
  )
}
