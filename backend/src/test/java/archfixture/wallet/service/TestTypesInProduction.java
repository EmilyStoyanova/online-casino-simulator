package archfixture.wallet.service;

import archfixture.wallet.dto.WalletTransactionType;
import archfixture.wallet.entity.TransactionType;

/** Violates R15: production code using the test-only ledger types. */
public class TestTypesInProduction {

    public Object[] types() {
        return new Object[] {WalletTransactionType.STARTING_BALANCE, WalletTransactionType.TEST_DEBIT, TransactionType.TEST_CREDIT};
    }
}
