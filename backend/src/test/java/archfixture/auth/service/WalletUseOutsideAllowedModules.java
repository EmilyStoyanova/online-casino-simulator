package archfixture.auth.service;

import archfixture.wallet.service.WalletService;

/** Violates R13: auth must not change balances. */
public class WalletUseOutsideAllowedModules {

    public long read(WalletService wallet) {
        return wallet.balance();
    }
}
