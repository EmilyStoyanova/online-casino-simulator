/**
 * <b>Casino games</b> module.
 *
 * <p>Server-authoritative game engines; European roulette is implemented (Week 4,
 * {@code docs/plans/week-04.md}). Outcomes are generated on the server with a cryptographically secure
 * RNG, and stakes and payouts are settled through the wallet module in the same database transaction
 * as the round. Virtual coins only.
 *
 * <ul>
 *   <li>{@code model}: the roulette rules and their domain types (plain Java, ArchUnit R17).</li>
 *   <li>{@code service}: {@code RouletteService}, which plays one round per request (validation, wallet
 *       lock, idempotent lookup, stake, draw, settlement, payout and the stored round), and the
 *       {@code SecureRandom} wheel.</li>
 *   <li>{@code entity}, {@code repository}: the append-only rounds and bets of Flyway V9.</li>
 *   <li>{@code dto}, {@code controller}: {@code POST /api/v1/games/roulette/spins} (players only, CSRF token
 *       and {@code Idempotency-Key} required).</li>
 * </ul>
 */
package com.casino.game;
