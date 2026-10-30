package archfixture.wallet.service;

import archfixture.game.service.GameCycle;

/** Violates R2 together with GameCycle. */
public class WalletCycle {

    public void call(GameCycle game) {
        game.call(this);
    }
}
