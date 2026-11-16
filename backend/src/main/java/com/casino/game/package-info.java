/**
 * <b>Casino games</b> module.
 *
 * <p>Server-authoritative game engines (European roulette first). Outcomes are generated on the server with a cryptographically secure RNG; payouts are calculated and settled through the wallet module in the same transaction.
 *
 * <p>Status: Week 4 in progress. {@code model} holds the roulette rules and their domain types (W4-04);
 * {@code service} the secure wheel. Rounds, persistence and the spin endpoint follow in W4-05 to W4-07
 * ({@code docs/plans/week-04.md}).
 */
package com.casino.game;
