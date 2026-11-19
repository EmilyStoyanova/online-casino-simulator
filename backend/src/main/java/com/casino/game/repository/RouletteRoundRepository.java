package com.casino.game.repository;

import java.util.Optional;
import java.util.UUID;

import com.casino.game.entity.RouletteRound;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/**
 * Database access to played roulette rounds ({@code roulette_rounds}, {@code roulette_bets}). Used only by
 * the {@code game} services.
 *
 * <p>Insert and read only: there is no update or delete method, matching the append-only triggers.
 */
public interface RouletteRoundRepository extends Repository<RouletteRound, UUID> {

    /**
     * Inserts a new round together with its bets (cascade) and flushes, so a database rule fails inside the
     * caller's transaction (W4-06). The round is new by construction ({@code Persistable}), so this is a
     * plain insert, never a merge.
     */
    RouletteRound saveAndFlush(RouletteRound round);

    /**
     * The round of a player's request, with its bets in request order, for idempotent replays
     * ({@code uq_roulette_rounds_request}). W4-06 calls it while holding the player's wallet lock.
     */
    @EntityGraph(attributePaths = "bets")
    Optional<RouletteRound> findByUserIdAndRequestId(UUID userId, UUID requestId);
}
