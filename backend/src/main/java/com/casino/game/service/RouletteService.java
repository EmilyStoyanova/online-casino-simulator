package com.casino.game.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.casino.game.entity.RouletteBet;
import com.casino.game.entity.RouletteRound;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteRules;
import com.casino.game.model.RouletteSelection;
import com.casino.game.model.RouletteSettlement;
import com.casino.game.model.RouletteWager;
import com.casino.game.model.SettledWager;
import com.casino.game.repository.RouletteRoundRepository;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plays one European roulette round for a player ({@code docs/plans/week-04.md} §7, §8). The only writer of
 * rounds (§5.7).
 *
 * <p>One database transaction, in this order:
 * <ol>
 *   <li>validate the wagers (1–10, 1–1,000 coins each, no selection twice): nothing is locked or written
 *       before;</li>
 *   <li>lock the player's wallet ({@link WalletService#lockWallet}), so requests of the same player are
 *       serialized before the idempotency lookup;</li>
 *   <li>look up the round of {@code (player, requestId)}: the same content returns the stored round (no
 *       wallet operation, no draw, no write); other content is {@code IDEMPOTENCY_KEY_REUSED};</li>
 *   <li>generate the round id; debit the total stake ({@code ROULETTE_STAKE}, key
 *       {@code roulette-round:<id>:stake}, reference the round id);</li>
 *   <li>only then draw the winning number ({@link RouletteWheel}) and settle the wagers
 *       ({@link RouletteRules});</li>
 *   <li>credit the total payout if positive ({@code ROULETTE_PAYOUT}, key {@code roulette-round:<id>:payout});</li>
 *   <li>insert the round and its bets with both ledger entry ids, flushed so a database rule fails here.</li>
 * </ol>
 * Any exception rolls everything back, wallet entries included; nothing is compensated by hand. The
 * result is built from the stored round, so a replay returns exactly the same {@link PlayedRound}. The
 * client's request id never reaches the ledger.
 */
@Service
public class RouletteService {

    /** Most bets in one spin (contract {@code bets.maxItems}, Q2). */
    public static final int MAX_BETS = 10;

    /** Largest amount of one bet in whole coins (contract {@code RouletteBet.amount.maximum}, Q2). */
    public static final long MAX_AMOUNT = 1000;

    private final WalletService walletService;
    private final RouletteRoundRepository rounds;
    private final RouletteWheel wheel;
    private final Clock clock;

    public RouletteService(WalletService walletService, RouletteRoundRepository rounds, RouletteWheel wheel, Clock clock) {
        this.walletService = walletService;
        this.rounds = rounds;
        this.wheel = wheel;
        this.clock = clock;
    }

    /**
     * Plays a round, or returns the stored round of an earlier request with the same id and content.
     *
     * @param userId    the session's player
     * @param requestId the client's {@code Idempotency-Key}
     * @param wagers    the bets in request order, validated by the API layer and again here
     * @return the stored round
     * @throws IllegalArgumentException      if the wagers break a limit or repeat a selection (the API rejects
     *                                       these first; here they would be a programming error)
     * @throws IdempotencyKeyReusedException if the request id belongs to a round with other content
     * @throws com.casino.wallet.exception.InsufficientFundsException    if the total stake exceeds the balance
     * @throws com.casino.wallet.exception.BalanceLimitExceededException if the payout would exceed the cap
     * @throws com.casino.wallet.exception.WalletBusyException           if the wallet stayed locked too long
     * @throws com.casino.wallet.exception.WalletNotFoundException       if the player has no wallet
     */
    @Transactional
    public PlayedRound spin(UUID userId, UUID requestId, List<RouletteWager> wagers) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        validate(wagers);

        walletService.lockWallet(userId);

        var earlier = rounds.findByUserIdAndRequestId(userId, requestId);
        if (earlier.isPresent()) {
            if (!sameContent(earlier.get(), wagers)) {
                throw new IdempotencyKeyReusedException();
            }
            return played(earlier.get());
        }

        UUID roundId = UUID.randomUUID();
        long totalStake = wagers.stream().mapToLong(RouletteWager::amount).reduce(0, Math::addExact);
        WalletOperationResult stake = walletService.debit(userId,
                new WalletOperation(WalletTransactionType.ROULETTE_STAKE, totalStake, ledgerKey(roundId, "stake"), roundId.toString()));

        RouletteSettlement settlement = RouletteRules.settle(wagers, wheel.spin());

        WalletOperationResult payout = null;
        if (settlement.totalPayout() > 0) {
            payout = walletService.credit(userId, new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, settlement.totalPayout(),
                    ledgerKey(roundId, "payout"), roundId.toString()));
        }
        requireNewEntry(stake);
        requireNewEntry(payout);

        List<RouletteBet> bets = new ArrayList<>(wagers.size());
        for (int i = 0; i < wagers.size(); i++) {
            bets.add(RouletteBet.of(wagers.get(i).selection(), wagers.get(i).amount(), settlement.payouts().get(i)));
        }
        RouletteRound round = rounds.saveAndFlush(new RouletteRound(roundId, userId, requestId, settlement.winningNumber(),
                settlement.totalStake(), settlement.totalPayout(), payout == null ? stake.balanceAfter() : payout.balanceAfter(),
                stake.transactionId(), payout == null ? null : payout.transactionId(), bets, clock.instant()));
        return played(round);
    }

    /** API limits and the no-selection-twice rule, checked before anything is locked or written. */
    private static void validate(List<RouletteWager> wagers) {
        Objects.requireNonNull(wagers, "wagers");
        if (wagers.isEmpty() || wagers.size() > MAX_BETS) {
            throw new IllegalArgumentException("A spin has 1 to " + MAX_BETS + " bets");
        }
        Set<RouletteSelection> selections = new HashSet<>();
        for (RouletteWager wager : wagers) {
            Objects.requireNonNull(wager, "wager");
            if (wager.amount() > MAX_AMOUNT) {
                throw new IllegalArgumentException("A bet is at most " + MAX_AMOUNT + " coins");
            }
            if (!selections.add(wager.selection())) {
                throw new IllegalArgumentException("A selection appears at most once per spin");
            }
        }
    }

    /**
     * Q17: the same content is the same set of (category, option, amount). The request is validated (no
     * selection twice), so the sets are well defined; the stored order does not matter.
     */
    private static boolean sameContent(RouletteRound round, List<RouletteWager> wagers) {
        Set<RouletteWager> stored = new HashSet<>();
        for (RouletteBet bet : round.getBets()) {
            stored.add(new RouletteWager(bet.selection(), bet.getAmount()));
        }
        return stored.size() == wagers.size() && stored.equals(new HashSet<>(wagers));
    }

    /** The stored round as the result; used for new rounds and replays alike. */
    private static PlayedRound played(RouletteRound round) {
        List<SettledWager> bets = round.getBets().stream()
                .map(bet -> new SettledWager(bet.selection(), bet.getAmount(), bet.getPayout())).toList();
        return new PlayedRound(round.getId(), round.getWinningNumber(), RouletteRules.colorOf(round.getWinningNumber()), bets,
                round.getTotalStake(), round.getTotalPayout(), round.getBalanceAfter(), round.getCreatedAt());
    }

    private static String ledgerKey(UUID roundId, String part) {
        return "roulette-round:" + roundId + ":" + part;
    }

    /**
     * The round id is new, so its ledger keys cannot have been used: a replayed wallet operation here would
     * mean that two rounds share an id. Fails the whole transaction instead of storing such a round.
     */
    private static void requireNewEntry(WalletOperationResult result) {
        if (result != null && result.replayed()) {
            throw new IllegalStateException("A new round's ledger entry already existed");
        }
    }
}
