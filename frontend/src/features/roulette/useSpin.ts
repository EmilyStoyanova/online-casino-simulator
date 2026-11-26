import { useEffect, useRef, useState, type Dispatch, type RefObject, type SetStateAction } from 'react'
import { SPIN_DURATION_MS } from '../../games/roulette/wheelConfig'
import { newIdempotencyKey } from '../../services/idempotency'
import { spinRoulette } from '../../services/rouletteApi'
import { ROULETTE_NUMBER_COLORS, type RouletteSpinRequest, type RouletteSpinResult } from '../../types/roulette'
import { useWallet } from '../wallet/useWallet'
import { toSpinRequest, type SlipBet } from './betSlipReducer'
import {
  clearPendingSpin,
  loadPendingSpin,
  removeInvalidPendingSpin,
  savePendingSpin,
  type PendingSpin,
} from './pendingSpinStore'
import { markRouletteNotFound } from './rouletteAvailability'
import { cellFor, selectionKey } from './rouletteTable'
import type { RouletteWheelHandle } from './RouletteWheel'
import { classifySpinError, type SpinFailure } from './spinErrors'

/**
 * The spin attempt of the signed-in player (§8.3): review → confirm → one new key and one frozen body
 * → pending record written → `POST` → the server's result, or an unresolved attempt that only an
 * explicit "Retry spin" sends again, with the same key and body.
 *
 * The server alone draws the number and computes payouts and the balance. The client never chooses a
 * number: the wheel turns only to the `winningNumber` of a `200`.
 */

export type SpinPhase =
  | { readonly kind: 'editing' }
  | { readonly kind: 'confirming' }
  | { readonly kind: 'submitting'; readonly attempt: PendingSpin; readonly retry: boolean }
  /** The server confirmed the round; the wheel turns to its number before the result is shown. */
  | { readonly kind: 'revealing'; readonly result: RouletteSpinResult }
  /** The outcome is unknown; betting is locked until "Retry spin" resolves it. */
  | { readonly kind: 'unresolved'; readonly attempt: PendingSpin; readonly message: string }

/** A definitive failure: nothing was played, the slip is editable again. */
export interface SpinError {
  readonly message: string
  /** Server validation messages by slip entry (`SlipBet.key`), or `null` for the request as a whole. */
  readonly fieldErrors: readonly { readonly betKey: string | null; readonly message: string }[]
}

export interface SpinState {
  readonly phase: SpinPhase
  /** The last round the server confirmed, shown until the next spin is sent. */
  readonly result: RouletteSpinResult | null
  readonly error: SpinError | null
}

export interface Spin {
  readonly state: SpinState
  /** "Review bets": editing → confirming. */
  review: () => void
  /** "Back to bets": confirming → editing; nothing else changes. */
  back: () => void
  /** "Confirm and spin": a new key, the frozen body of `bets`, the record, then the request. */
  confirm: (bets: readonly SlipBet[]) => void
  /** "Retry spin": the unresolved attempt again, with its own key and body. */
  retry: () => void
  /** Re-reads the wallet (never clears an attempt). */
  checkBalance: () => void
  dismissError: () => void
}

export const EARLIER_SPIN_MESSAGE =
  'An earlier spin could not be confirmed. Retry it to get the confirmed result — a retry never charges twice.'
export const INVALID_RECORD_MESSAGE =
  'An earlier spin could not be checked safely and was set aside. Your wallet history shows whether it was played.'
export const NOT_SAVED_MESSAGE =
  'This spin was not sent: your browser could not save it safely, which is needed to recover a lost answer. Nothing was charged.'
export const KEY_FAILED_MESSAGE = 'This browser cannot create a secure spin id, so the spin was not sent. Nothing was charged.'

/** The longest the result waits for the wheel (it may still be loading, or never draw). */
export const REVEAL_LIMIT_MS = SPIN_DURATION_MS + 3000

type FlightOutcome =
  | { readonly kind: 'settled'; readonly result: RouletteSpinResult }
  | { readonly kind: 'failed'; readonly failure: SpinFailure; readonly kept: PendingSpin | null }

interface Flight {
  readonly record: PendingSpin
  readonly outcome: Promise<FlightOutcome>
}

interface WalletActions {
  applyConfirmedBalance: (balance: number) => void
  refresh: () => void
}

/**
 * Requests in flight, by user id. Module state on purpose: the answer must be handled once even if the
 * page is left and opened again meanwhile (no second request, and the new page shows "Waiting for the
 * server…" for it). At most one per user: confirm and retry refuse while one exists.
 */
const flights = new Map<string, Flight>()

/** Tests only: forget requests of an earlier test that never got an answer. */
export function resetSpinFlights(): void {
  flights.clear()
}

const isCoins = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0

/** A `200` body that can be shown and applied; anything else is treated as an unknown outcome. */
function isSpinResult(value: unknown): value is RouletteSpinResult {
  if (typeof value !== 'object' || value === null) return false
  const result = value as Partial<RouletteSpinResult>
  if (
    typeof result.roundId !== 'string' ||
    typeof result.winningNumber !== 'number' ||
    !Number.isInteger(result.winningNumber) ||
    result.winningNumber < 0 ||
    result.winningNumber > 36 ||
    !(ROULETTE_NUMBER_COLORS as readonly unknown[]).includes(result.winningColor) ||
    !Array.isArray(result.bets) ||
    result.bets.length === 0 ||
    !isCoins(result.totalStake) ||
    !isCoins(result.totalPayout) ||
    !isCoins(result.balance) ||
    typeof result.createdAt !== 'string'
  ) {
    return false
  }
  return result.bets.every((bet) => {
    try {
      cellFor(bet)
      return isCoins(bet.amount) && isCoins(bet.payout)
    } catch {
      return false
    }
  })
}

function finish(record: PendingSpin): void {
  if (flights.get(record.userId)?.record === record) flights.delete(record.userId)
}

/** Applies a failure to the record (§8.4) and the wallet; returns the record that is kept, if any. */
function settleFailure(record: PendingSpin, failure: SpinFailure, wallet: WalletActions): FlightOutcome {
  let kept: PendingSpin | null = null
  if (failure.recordEffect === 'resolves' || (failure.recordEffect === 'before-lookup' && !record.ambiguous)) {
    clearPendingSpin(record.userId, record.idempotencyKey)
  } else {
    // Unknown outcome, or rejected before the key lookup while an earlier send may have played.
    kept = Object.freeze({ ...record, ambiguous: record.ambiguous || failure.recordEffect === 'unknown', state: 'unresolved' })
    savePendingSpin(kept) // if this fails, the stored `sending` record reads back as unresolved and ambiguous
  }
  if (failure.refreshBalance) wallet.refresh()
  if (failure.unavailable) markRouletteNotFound()
  if (failure.code === 'IDEMPOTENCY_KEY_REUSED') {
    // Impossible with a frozen body (§8.4): a defect worth seeing in the console. The key is no secret.
    console.error(`Roulette spin ${record.idempotencyKey} was answered 409 IDEMPOTENCY_KEY_REUSED.`)
  }
  return { kind: 'failed', failure, kept }
}

/**
 * Sends the record and handles the answer **once**, independent of any component: the record and the
 * wallet are updated even if the page was left. `wallet` belongs to the session that sent the spin, so
 * a late answer never changes another player's wallet.
 */
function startFlight(record: PendingSpin, wallet: WalletActions): Flight {
  const outcome = spinRoulette(record.request, record.idempotencyKey).then(
    (result: unknown): FlightOutcome => {
      finish(record)
      if (!isSpinResult(result)) return settleFailure(record, classifySpinError(result), wallet)
      // Validated above, before anything is changed: only now is the record cleared and the balance applied.
      clearPendingSpin(record.userId, record.idempotencyKey)
      try {
        wallet.applyConfirmedBalance(result.balance) // the server's value, at once (Q3); then reconciled
      } catch {
        wallet.refresh() // cannot happen for a validated body; the server's balance is read instead
      }
      return { kind: 'settled', result }
    },
    (error: unknown): FlightOutcome => {
      finish(record)
      return settleFailure(record, classifySpinError(error), wallet)
    },
  )
  const flight: Flight = { record, outcome }
  flights.set(record.userId, flight)
  return flight
}

interface View {
  mounted: RefObject<boolean>
  setState: Dispatch<SetStateAction<SpinState>>
  onSettled: () => void
  wheel: RefObject<RouletteWheelHandle | null>
}

function toSpinError(failure: SpinFailure, request: RouletteSpinRequest): SpinError | null {
  if (failure.unavailable) return null // the page shows its unavailable state instead
  return {
    message: failure.message,
    fieldErrors: failure.fieldErrors.map(({ betIndex, message }) => {
      const bet = betIndex === null ? undefined : request.bets[betIndex]
      return { betKey: bet ? selectionKey(bet) : null, message }
    }),
  }
}

/** Turns the wheel to the server's number, then shows the result; never longer than `REVEAL_LIMIT_MS`. */
function reveal(result: RouletteSpinResult, view: View): void {
  view.setState({ phase: { kind: 'revealing', result }, result: null, error: null })
  let timer: ReturnType<typeof setTimeout> | undefined
  const limit = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, REVEAL_LIMIT_MS)
  })
  const handle = view.wheel.current
  // Without a wheel (no canvas, failed load) or if it fails, the HTML result is shown at once.
  const turn = handle ? handle.spinTo(result.winningNumber) : Promise.reject(new Error('The wheel is not shown.'))
  void Promise.race([turn, limit])
    .catch(() => undefined)
    .then(() => {
      clearTimeout(timer)
      if (!view.mounted.current) return
      view.setState((state) =>
        state.phase.kind === 'revealing' && state.phase.result === result ? { phase: { kind: 'editing' }, result, error: null } : state,
      )
    })
}

function showOutcome(outcome: FlightOutcome, record: PendingSpin, view: View): void {
  if (!view.mounted.current) return
  if (outcome.kind === 'settled') {
    view.onSettled()
    reveal(outcome.result, view)
    return
  }
  const { failure, kept } = outcome
  if (kept) {
    const message =
      failure.kind === 'uncertain'
        ? failure.message
        : `${failure.message} Your earlier spin is still not confirmed: retry it once this is solved — a retry never charges twice.`
    view.setState({ phase: { kind: 'unresolved', attempt: kept, message }, result: null, error: null })
  } else {
    view.setState({ phase: { kind: 'editing' }, result: null, error: toSpinError(failure, record.request) })
  }
}

interface Initial {
  readonly state: SpinState
  readonly invalidRecord: boolean
}

function initial(userId: string): Initial {
  const flight = flights.get(userId)
  if (flight) {
    return { state: { phase: { kind: 'submitting', attempt: flight.record, retry: true }, result: null, error: null }, invalidRecord: false }
  }
  const lookup = loadPendingSpin(userId)
  if (lookup.kind === 'found') {
    return {
      state: { phase: { kind: 'unresolved', attempt: lookup.record, message: EARLIER_SPIN_MESSAGE }, result: null, error: null },
      invalidRecord: false,
    }
  }
  return {
    state: {
      phase: { kind: 'editing' },
      result: null,
      error: lookup.kind === 'invalid' ? { message: INVALID_RECORD_MESSAGE, fieldErrors: [] } : null,
    },
    invalidRecord: lookup.kind === 'invalid',
  }
}

/**
 * The spin attempt of `userId` (render it per player: the roulette page keys its table by the player).
 * `onSettled` (stable) clears the slip after a confirmed round; `wheel` is the wheel's handle.
 * Nothing is ever sent automatically: on load an unresolved attempt only offers "Retry spin".
 */
export function useSpin({
  userId,
  wheel,
  onSettled,
}: {
  userId: string
  wheel: RefObject<RouletteWheelHandle | null>
  onSettled: () => void
}): Spin {
  const [start] = useState(() => initial(userId))
  const [state, setState] = useState<SpinState>(start.state)
  const wallet = useWallet()
  const mounted = useRef(false)
  // The flight whose answer this component shows (StrictMode remounts must not attach twice).
  const attached = useRef<Flight | null>(null)

  useEffect(() => {
    mounted.current = true
    // Opened again while this player's request is still in flight: show its answer when it comes.
    const flight = flights.get(userId)
    if (flight && attached.current !== flight) {
      attached.current = flight
      void flight.outcome.then((outcome) => showOutcome(outcome, flight.record, { mounted, setState, onSettled, wheel }))
    }
    return () => {
      mounted.current = false
    }
  }, [userId, onSettled, wheel])

  const { refresh } = wallet
  // A set-aside record: re-read the wallet, unless its first read of this page load is still running.
  const [rereadWallet] = useState(() => start.invalidRecord && wallet.state.status !== 'loading')
  useEffect(() => {
    if (!start.invalidRecord) return
    removeInvalidPendingSpin(userId)
    if (rereadWallet) refresh()
  }, [start, userId, refresh, rereadWallet])

  const send = (attempt: PendingSpin, retry: boolean) => {
    if (flights.has(userId)) return // one request at a time, also across a remount
    const record: PendingSpin = Object.freeze({ ...attempt, state: 'sending' })
    if (!savePendingSpin(record)) {
      // Persist before sending (§8.3 rule 2): without the record a lost answer could not be retried.
      setState(
        retry
          ? { phase: { kind: 'unresolved', attempt, message: `${NOT_SAVED_MESSAGE} Try again in a moment.` }, result: null, error: null }
          : { phase: { kind: 'editing' }, result: null, error: { message: NOT_SAVED_MESSAGE, fieldErrors: [] } },
      )
      return
    }
    const flight = startFlight(record, { applyConfirmedBalance: wallet.applyConfirmedBalance, refresh: wallet.refresh })
    attached.current = flight
    setState({ phase: { kind: 'submitting', attempt: record, retry }, result: null, error: null })
    void flight.outcome.then((outcome) => showOutcome(outcome, record, { mounted, setState, onSettled, wheel }))
  }

  return {
    state,
    review: () => setState((current) => (current.phase.kind === 'editing' ? { ...current, phase: { kind: 'confirming' }, error: null } : current)),
    back: () => setState((current) => (current.phase.kind === 'confirming' ? { ...current, phase: { kind: 'editing' } } : current)),
    confirm: (bets) => {
      if (state.phase.kind !== 'confirming' || flights.has(userId)) return
      let request: RouletteSpinRequest
      let idempotencyKey: string
      try {
        request = toSpinRequest(bets) // frozen: exactly this body is sent on every retry
      } catch {
        setState({ phase: { kind: 'editing' }, result: null, error: { message: 'These bets cannot be sent. Check them and review again.', fieldErrors: [] } })
        return
      }
      try {
        idempotencyKey = newIdempotencyKey() // one new key per new spin, never for a retry
      } catch {
        setState({ phase: { kind: 'editing' }, result: null, error: { message: KEY_FAILED_MESSAGE, fieldErrors: [] } })
        return
      }
      send(
        Object.freeze({ version: 1, userId, idempotencyKey, request, createdAt: new Date().toISOString(), ambiguous: false, state: 'sending' }),
        false,
      )
    },
    retry: () => {
      if (state.phase.kind !== 'unresolved') return
      send(state.phase.attempt, true)
    },
    checkBalance: refresh,
    dismissError: () => setState((current) => (current.error ? { ...current, error: null } : current)),
  }
}
