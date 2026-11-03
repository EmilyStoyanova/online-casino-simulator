/** Shared button looks, for `<button>` and for links styled as buttons. */
const base =
  'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 disabled:cursor-not-allowed disabled:opacity-50'

export const primaryButton = `${base} bg-emerald-500 text-zinc-950 shadow-lg shadow-emerald-500/20 hover:bg-emerald-400`

export const secondaryButton = `${base} border border-zinc-700 text-zinc-200 hover:bg-zinc-800`

export const ghostButton = `${base} text-zinc-300 hover:bg-zinc-800/70 hover:text-zinc-100`
