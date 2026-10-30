package archfixture.game.service;

import archfixture.wallet.service.WalletCycle;

/** Violates R2 together with WalletCycle. */
public class GameCycle {

    public void call(WalletCycle wallet) {
        wallet.hashCode();
    }
}
