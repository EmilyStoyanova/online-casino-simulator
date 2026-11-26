import { CATEGORY_RULES, chanceText, HOUSE_EDGE_PERCENT, POCKETS, RETURN_TO_PLAYER_PERCENT } from './oddsRules'

/**
 * "Odds and rules" (§5.8, Q8, AC-W5-08.5): a native `<details>` disclosure (keyboard and screen-reader
 * operable without code) with the five categories, what each covers, the chance of a win, the odds,
 * what a win returns, the role of zero and the house edge of 1/37. Static text from the contract; it
 * explains the rules and computes nothing for the player's bets.
 */
export function OddsInfo() {
  return (
    <details className="group rounded-xl border border-zinc-800 bg-zinc-950/40 px-4">
      <summary className="flex min-h-11 cursor-pointer items-center justify-between gap-2 rounded-md text-sm font-semibold text-zinc-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400">
        Odds and rules
        <span aria-hidden="true" className="text-zinc-400 transition group-open:rotate-180">
          ▾
        </span>
      </summary>
      <div className="space-y-4 pb-4 pt-1 text-sm leading-relaxed text-zinc-300">
        <p>
          The server draws one of {POCKETS} numbers, 0 to 36, each equally likely. The figures below explain the rules;
          the server computes every result and payout.
        </p>
        <ul aria-label="Bet types" className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {CATEGORY_RULES.map((rule) => (
            <li key={rule.category} className="rounded-lg border border-zinc-800 px-3 py-2">
              <h3 className="font-semibold text-zinc-100">{rule.name}</h3>
              <dl className="mt-1 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs">
                <dt className="text-zinc-400">Covers</dt>
                <dd className="text-zinc-200">{rule.covers}</dd>
                <dt className="text-zinc-400">Chance of a win</dt>
                <dd className="text-zinc-200">{chanceText(rule.winningNumbers)}</dd>
                <dt className="text-zinc-400">Odds</dt>
                <dd className="text-zinc-200">{rule.netOdds} to 1</dd>
                <dt className="text-zinc-400">A win returns</dt>
                <dd className="text-zinc-200">{rule.returnedPerCoin} × the stake (stake included)</dd>
              </dl>
            </li>
          ))}
        </ul>
        <div>
          <h3 className="font-semibold text-zinc-100">Zero</h3>
          <p>
            Zero is green. It wins only a straight bet on 0; red or black, even or odd, low or high and every dozen lose
            when zero comes up.
          </p>
        </div>
        <div>
          <h3 className="font-semibold text-zinc-100">House edge</h3>
          <p>
            Every bet has the same house edge of 1/37, about {HOUSE_EDGE_PERCENT} %: on average, {POCKETS - 1}/{POCKETS} of all
            stakes (about {RETURN_TO_PLAYER_PERCENT} %) comes back. For a straight bet: 35 × 1/37 − 1 × 36/37 = −1/37. No bet
            or combination of bets changes this.
          </p>
        </div>
        <p>
          Every spin is independent: earlier numbers do not make any number more or less likely. Virtual coins have no
          monetary value.
        </p>
      </div>
    </details>
  )
}
