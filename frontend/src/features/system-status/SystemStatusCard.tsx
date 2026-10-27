import type { ReactNode } from 'react'
import type { ComponentStatus } from '../../types/system'
import { useSystemStatus } from './useSystemStatus'

function StatusBadge({ status }: { status: ComponentStatus }) {
  const styles =
    status === 'UP'
      ? 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/40'
      : 'bg-rose-500/15 text-rose-300 ring-rose-500/40'
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ${styles}`}>{status}</span>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <dt className="text-sm text-zinc-400">{label}</dt>
      <dd className="text-sm font-medium text-zinc-100">{children}</dd>
    </div>
  )
}

/** Shows whether the frontend can reach the backend and the backend can reach its database. */
export function SystemStatusCard() {
  const { state, refresh } = useSystemStatus()

  return (
    <section
      aria-labelledby="system-status-heading"
      className="w-full max-w-md rounded-2xl border border-zinc-800 bg-zinc-900/70 p-6 shadow-xl"
    >
      <div className="mb-4 flex items-center justify-between">
        <h2 id="system-status-heading" className="text-lg font-semibold text-zinc-100">
          Platform status
        </h2>
        <button
          type="button"
          onClick={refresh}
          disabled={state.kind === 'loading'}
          className="rounded-lg border border-zinc-700 px-3 py-1.5 text-sm text-zinc-200 transition hover:bg-zinc-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Refresh
        </button>
      </div>

      <div role="status" aria-live="polite">
        {state.kind === 'loading' && <p className="text-sm text-zinc-400">Checking backend…</p>}

        {state.kind === 'error' && (
          <p className="text-sm text-rose-300">
            Backend unavailable: <span className="text-zinc-300">{state.message}</span>
          </p>
        )}

        {state.kind === 'success' && (
          <dl className="divide-y divide-zinc-800">
            <Row label="Backend">
              <StatusBadge status={state.data.status} />
            </Row>
            <Row label="Database">
              <StatusBadge status={state.data.database.status} />
            </Row>
            <Row label="Schema version">{state.data.database.schemaVersion ?? '—'}</Row>
            <Row label="API version">{state.data.version}</Row>
          </dl>
        )}
      </div>
    </section>
  )
}
