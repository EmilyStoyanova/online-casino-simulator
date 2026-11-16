package com.casino.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.casino.game.entity.RouletteBet;
import com.casino.game.entity.RouletteRound;
import com.casino.game.model.PlayedRound;
import com.casino.game.model.RouletteBetColor;
import com.casino.game.model.RouletteDozen;
import com.casino.game.model.RouletteNumberColor;
import com.casino.game.model.RouletteSelection.Dozen;
import com.casino.game.model.RouletteSelection.RedBlack;
import com.casino.game.model.RouletteSelection.Straight;
import com.casino.game.model.RouletteWager;
import com.casino.game.model.SettledWager;
import com.casino.game.repository.RouletteRoundRepository;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.exception.IdempotencyKeyReusedException;
import com.casino.wallet.exception.InsufficientFundsException;
import com.casino.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** The order and the decisions of a spin (W4-06, §7.1), with the wallet, the repository and the wheel mocked. */
class RouletteServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REQUEST = UUID.fromString("3f2b8c4e-9d1a-4e7b-8c2f-5a6d7e8f9a0b");
    private static final Instant NOW = Instant.parse("2026-11-20T10:15:30.123456789Z");

    private final WalletService wallet = mock(WalletService.class);
    private final RouletteRoundRepository rounds = mock(RouletteRoundRepository.class);
    private final RouletteWheel wheel = mock(RouletteWheel.class);
    private RouletteService service;

    @BeforeEach
    void setUp() {
        service = new RouletteService(wallet, rounds, wheel, Clock.fixed(NOW, ZoneOffset.UTC));
        when(rounds.findByUserIdAndRequestId(PLAYER, REQUEST)).thenReturn(Optional.empty());
        when(rounds.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void aWinningSpinLocksLooksUpDebitsDrawsCreditsAndStoresInThisOrder() {
        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 970, false));
        when(wheel.spin()).thenReturn(17);
        when(wallet.credit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(42, 1330, false));

        PlayedRound round = service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Straight(17), 10),
                new RouletteWager(new RedBlack(RouletteBetColor.RED), 20)));

        InOrder order = inOrder(wallet, rounds, wheel);
        order.verify(wallet).lockWallet(PLAYER);
        order.verify(rounds).findByUserIdAndRequestId(PLAYER, REQUEST);
        ArgumentCaptor<WalletOperation> debit = ArgumentCaptor.forClass(WalletOperation.class);
        order.verify(wallet).debit(eq(PLAYER), debit.capture());
        order.verify(wheel).spin();
        ArgumentCaptor<WalletOperation> credit = ArgumentCaptor.forClass(WalletOperation.class);
        order.verify(wallet).credit(eq(PLAYER), credit.capture());
        ArgumentCaptor<RouletteRound> stored = ArgumentCaptor.forClass(RouletteRound.class);
        order.verify(rounds).saveAndFlush(stored.capture());

        UUID roundId = stored.getValue().getId();
        assertThat(roundId).isNotEqualTo(REQUEST);
        // Ledger keys and references name the server's round id, never the client's request id.
        assertThat(debit.getValue()).isEqualTo(new WalletOperation(WalletTransactionType.ROULETTE_STAKE, 30,
                "roulette-round:" + roundId + ":stake", roundId.toString()));
        assertThat(credit.getValue()).isEqualTo(new WalletOperation(WalletTransactionType.ROULETTE_PAYOUT, 360,
                "roulette-round:" + roundId + ":payout", roundId.toString()));
        assertThat(debit.getValue().idempotencyKey() + credit.getValue().idempotencyKey()).doesNotContain(REQUEST.toString());
        RouletteRound row = stored.getValue();
        assertThat(row.getUserId()).isEqualTo(PLAYER);
        assertThat(row.getRequestId()).isEqualTo(REQUEST);
        assertThat(row.getStakeTransactionId()).isEqualTo(41);
        assertThat(row.getPayoutTransactionId()).isEqualTo(42);
        assertThat(row.getBalanceAfter()).isEqualTo(1330);
        assertThat(row.getCreatedAt()).isEqualTo(Instant.parse("2026-11-20T10:15:30.123456Z"));

        assertThat(round).isEqualTo(new PlayedRound(roundId, 17, RouletteNumberColor.BLACK,
                List.of(new SettledWager(new Straight(17), 10, 360), new SettledWager(new RedBlack(RouletteBetColor.RED), 20, 0)),
                30, 360, 1330, Instant.parse("2026-11-20T10:15:30.123456Z")));
    }

    @Test
    void aLosingSpinHasNoCreditAndKeepsTheBalanceAfterTheStake() {
        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 950, false));
        when(wheel.spin()).thenReturn(8);

        PlayedRound round = service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Dozen(RouletteDozen.THIRD), 50)));

        verify(wallet, never()).credit(any(), any());
        ArgumentCaptor<RouletteRound> stored = ArgumentCaptor.forClass(RouletteRound.class);
        verify(rounds).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getPayoutTransactionId()).isNull();
        assertThat(round.totalPayout()).isZero();
        assertThat(round.balance()).isEqualTo(950);
    }

    @Test
    void theSameContentReturnsTheStoredRoundWithoutAnyWalletOperationOrDraw() {
        RouletteRound earlier = storedRound(List.of(RouletteBet.of(new Straight(17), 10, 360),
                RouletteBet.of(new RedBlack(RouletteBetColor.RED), 20, 0)));
        when(rounds.findByUserIdAndRequestId(PLAYER, REQUEST)).thenReturn(Optional.of(earlier));

        // Reordered, as Q17 allows.
        PlayedRound replay = service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new RedBlack(RouletteBetColor.RED), 20),
                new RouletteWager(new Straight(17), 10)));

        verify(wallet).lockWallet(PLAYER);
        verify(wallet, never()).debit(any(), any());
        verify(wallet, never()).credit(any(), any());
        verifyNoInteractions(wheel);
        verify(rounds, never()).saveAndFlush(any());
        assertThat(replay.roundId()).isEqualTo(earlier.getId());
        assertThat(replay.bets()).extracting(SettledWager::selection)
                .containsExactly(new Straight(17), new RedBlack(RouletteBetColor.RED)); // the original order
        assertThat(replay.balance()).isEqualTo(1330);
    }

    @Test
    void otherContentWithTheSameRequestIdIsAConflictAndChangesNothing() {
        RouletteRound earlier = storedRound(List.of(RouletteBet.of(new Straight(17), 10, 360),
                RouletteBet.of(new RedBlack(RouletteBetColor.RED), 20, 0)));
        when(rounds.findByUserIdAndRequestId(PLAYER, REQUEST)).thenReturn(Optional.of(earlier));

        for (List<RouletteWager> other : List.of(
                List.of(new RouletteWager(new Straight(17), 10)), // a bet missing
                List.of(new RouletteWager(new Straight(17), 11), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20)), // amount
                List.of(new RouletteWager(new Straight(18), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20)), // number
                List.of(new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.BLACK), 20)), // option
                List.of(new RouletteWager(new Straight(17), 10), new RouletteWager(new RedBlack(RouletteBetColor.RED), 20),
                        new RouletteWager(new Dozen(RouletteDozen.FIRST), 5)))) { // a bet more
            assertThatThrownBy(() -> service.spin(PLAYER, REQUEST, other)).isInstanceOf(IdempotencyKeyReusedException.class);
        }
        verify(wallet, never()).debit(any(), any());
        verifyNoInteractions(wheel);
        verify(rounds, never()).saveAndFlush(any());
    }

    @Test
    void insufficientFundsStopsTheSpinBeforeTheWheel() {
        when(wallet.debit(eq(PLAYER), any())).thenThrow(new InsufficientFundsException());

        assertThatThrownBy(() -> service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Straight(1), 1000))))
                .isInstanceOf(InsufficientFundsException.class);

        verifyNoInteractions(wheel);
        verify(wallet, never()).credit(any(), any());
        verify(rounds, never()).saveAndFlush(any());
    }

    @Test
    void invalidWagersAreRejectedBeforeAnythingIsLocked() {
        RouletteWager red = new RouletteWager(new RedBlack(RouletteBetColor.RED), 10);
        assertThatIllegalArgumentException().isThrownBy(() -> service.spin(PLAYER, REQUEST, List.of()))
                .withMessage("A spin has 1 to 10 bets");
        List<RouletteWager> eleven = new ArrayList<>();
        for (int n = 0; n <= 10; n++) {
            eleven.add(new RouletteWager(new Straight(n), 1));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> service.spin(PLAYER, REQUEST, eleven));
        assertThatIllegalArgumentException().isThrownBy(() -> service.spin(PLAYER, REQUEST,
                List.of(new RouletteWager(new Straight(1), 1001)))).withMessage("A bet is at most 1000 coins");
        // The same selection twice, also with different amounts.
        assertThatIllegalArgumentException().isThrownBy(() -> service.spin(PLAYER, REQUEST,
                List.of(red, new RouletteWager(new RedBlack(RouletteBetColor.RED), 5)))).withMessage("A selection appears at most once per spin");
        assertThatThrownBy(() -> service.spin(PLAYER, REQUEST, Collections.singletonList(null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.spin(null, REQUEST, List.of(red))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.spin(PLAYER, null, List.of(red))).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(wallet, rounds, wheel);
        // Ten bets of 1,000 coins are the largest valid spin.
        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 0, false));
        when(wheel.spin()).thenReturn(0);
        List<RouletteWager> largest = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            largest.add(new RouletteWager(new Straight(n), 1000));
        }
        assertThat(service.spin(PLAYER, REQUEST, largest).totalStake()).isEqualTo(10_000);
    }

    @Test
    void anInvalidNumberFromTheWheelFailsTheSpinBeforeAnyCredit() {
        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 990, false));
        when(wheel.spin()).thenReturn(37);

        assertThatIllegalArgumentException().isThrownBy(() -> service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Straight(1), 10))));

        verify(wallet, never()).credit(any(), any());
        verify(rounds, never()).saveAndFlush(any());
    }

    @Test
    void aReplayedLedgerEntryForANewRoundIsABug() {
        // A new round id cannot have ledger entries yet; if the wallet replays one, the round is not stored.
        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 990, true));
        when(wheel.spin()).thenReturn(1);
        when(wallet.credit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(42, 1350, false));

        assertThatIllegalStateException().isThrownBy(() -> service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Straight(1), 10))));
        verify(rounds, never()).saveAndFlush(any());

        when(wallet.debit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(41, 990, false));
        when(wallet.credit(eq(PLAYER), any())).thenReturn(new WalletOperationResult(42, 1350, true));
        assertThatIllegalStateException().isThrownBy(() -> service.spin(PLAYER, REQUEST, List.of(new RouletteWager(new Straight(1), 10))));
        verify(rounds, never()).saveAndFlush(any());
    }

    private static RouletteRound storedRound(List<RouletteBet> bets) {
        return new RouletteRound(UUID.randomUUID(), PLAYER, REQUEST, 17, bets.stream().mapToLong(RouletteBet::getAmount).sum(),
                bets.stream().mapToLong(RouletteBet::getPayout).sum(), 1330, 41, 42L, bets, NOW);
    }
}
