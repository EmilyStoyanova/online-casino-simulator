package com.casino.game.entity;

import java.util.Objects;

import com.casino.game.model.RouletteBetCategory;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteParity;
import com.casino.game.model.RouletteRange;
import com.casino.game.model.RouletteSelection;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.EvenOdd;
import com.casino.game.model.RouletteSelection.LowHigh;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * One bet of a played round ({@code roulette_bets}, V9). Written once together with its round and never
 * changed: no setters, {@link Immutable}, and the database rejects {@code UPDATE} and {@code DELETE}
 * (trigger {@code trg_roulette_bets_append_only}).
 *
 * <p>Storage of the option (Q19): {@code STRAIGHT} uses {@code number}, the other categories use
 * {@code selection} (the name of their option enum); {@code ck_roulette_bets_option} allows exactly these
 * pairs. {@link #of} and {@link #selection()} convert to and from {@link RouletteSelection}, so the rest of
 * the module works with the domain type. The identity id keeps the order of the bets in the request.
 * The round is the owner of the bets (unidirectional, {@link RouletteRound#getBets()}); a bet has no
 * reference back.
 */
@Entity
@Immutable
@Table(name = "roulette_bets")
public class RouletteBet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 10)
    private RouletteBetCategory category;

    @Column(name = "selection", updatable = false, length = 6)
    private String selection;

    @Column(name = "number", updatable = false)
    private Short number;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "payout", nullable = false, updatable = false)
    private long payout;

    /** For JPA. */
    protected RouletteBet() {
    }

    private RouletteBet(RouletteBetCategory category, String selection, Short number, long amount, long payout) {
        this.category = category;
        this.selection = selection;
        this.number = number;
        this.amount = amount;
        this.payout = payout;
    }

    /**
     * A bet to be stored with its round.
     *
     * @param selection what the bet was placed on
     * @param amount    stake in whole coins
     * @param payout    coins credited back for it: stake plus net winnings on a win, 0 on a loss
     */
    public static RouletteBet of(RouletteSelection selection, long amount, long payout) {
        Objects.requireNonNull(selection, "selection");
        return switch (selection) {
            case Straight straight -> new RouletteBet(straight.category(), null, (short) straight.number(), amount, payout);
            case RedBlack redBlack -> new RouletteBet(redBlack.category(), redBlack.color().name(), null, amount, payout);
            case EvenOdd evenOdd -> new RouletteBet(evenOdd.category(), evenOdd.parity().name(), null, amount, payout);
            case LowHigh lowHigh -> new RouletteBet(lowHigh.category(), lowHigh.range().name(), null, amount, payout);
            case Dozen dozen -> new RouletteBet(dozen.category(), dozen.dozen().name(), null, amount, payout);
        };
    }

    public Long getId() {
        return id;
    }

    public RouletteBetCategory getCategory() {
        return category;
    }

    /** The stored option as the domain type. */
    public RouletteSelection selection() {
        return switch (category) {
            case STRAIGHT -> new Straight(number);
            case RED_BLACK -> new RedBlack(RouletteBetColor.valueOf(selection));
            case EVEN_ODD -> new EvenOdd(RouletteParity.valueOf(selection));
            case LOW_HIGH -> new LowHigh(RouletteRange.valueOf(selection));
            case DOZEN -> new Dozen(RouletteDozen.valueOf(selection));
        };
    }

    public long getAmount() {
        return amount;
    }

    public long getPayout() {
        return payout;
    }
}
