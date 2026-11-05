import { AccountPanel } from '../features/auth/AccountPanel'
import { SystemStatusCard } from '../features/system-status/SystemStatusCard'

export function HomePage() {
  return (
    <div className="flex flex-col items-center gap-10 text-center">
      <div className="max-w-2xl space-y-4">
        <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
          Learn the odds. <span className="text-emerald-400">Play without risk.</span>
        </h1>
        <p className="text-base text-zinc-400 sm:text-lg">
          Authentic European roulette rules and probabilities, educational content and responsible-play
          tools — powered entirely by virtual coins.
        </p>
      </div>
      {/* grid-cols-1 = minmax(0, 1fr): a long email is truncated instead of widening the page. */}
      <div className="grid w-full max-w-4xl grid-cols-1 items-start justify-items-center gap-6 md:grid-cols-2">
        <AccountPanel />
        <SystemStatusCard />
      </div>
    </div>
  )
}
