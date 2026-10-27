# ADR-0003: Server-authoritative game logic and wallet

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

A casino simulator is only credible if outcomes are fair and balances are correct. Anything computed
in the browser can be manipulated. Network retries and double clicks can cause duplicated
operations.

## Decision

1. The backend alone generates game outcomes, using a cryptographically secure RNG
   (`java.security.SecureRandom`), with all 37 European roulette outcomes equally likely.
2. The backend alone validates bets, calculates payouts and changes balances. The frontend sends
   intents (bets) and renders **server-confirmed** results; animations are synchronised to the
   server's result.
3. Balances are stored as integer virtual coins (`bigint`), never floating point.
4. Every balance change is a row in an **immutable ledger** (`wallet_transactions`); the balance and
   the ledger are updated in the same database transaction with row locking / optimistic versioning.
5. State-changing requests carry an **idempotency key**; a unique constraint guarantees a round or
   reward is processed at most once.
6. Responsible-play limits are checked on the server before every game action.

## Consequences

- ✅ Fairness and correctness do not depend on client behaviour.
- ✅ Core logic is testable without a UI (the plan's key Week 4 milestone).
- ⚠️ Every game action needs a server round trip; acceptable for roulette.
- ⚠️ Requires concurrency tests for the wallet (planned for Weeks 3 and 11).
