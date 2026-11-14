package com.casino.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.casino.Concurrency;
import com.casino.TestDatabase;
import com.casino.TestcontainersConfiguration;
import com.casino.user.dto.NewAccount;
import com.casino.user.dto.UserRegistered;
import com.casino.user.service.UserAccountService;
import com.casino.wallet.WalletTestData;
import com.casino.wallet.dto.WalletOperation;
import com.casino.wallet.dto.WalletOperationResult;
import com.casino.wallet.dto.WalletTransactionType;
import com.casino.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opening a player's wallet at registration (W3-05, AC-1.1–AC-1.5) against PostgreSQL, through the
 * real {@code UserRegistered} publication of {@link UserAccountService}. Runs with a starting balance
 * of 250 coins, so the amount provably comes from {@code app.wallet.starting-balance} (the default of
 * 1,000 is covered by {@code RegistrationIT}).
 */
@SpringBootTest(properties = "app.wallet.starting-balance=250")
@Import(TestcontainersConfiguration.class)
class WalletOpeningIT {

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDatabase testDatabase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private WalletTestData data;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        data = new WalletTestData(jdbcTemplate, wallets, walletService, transactions);
    }

    @AfterEach
    void resetDatabase() {
        testDatabase.reset();
    }

    @Test
    void aRegisteredPlayerGetsOneWalletWithTheConfiguredStartingBalance() {
        UUID player = register("alice@example.com");

        assertThat(walletState(player)).isEqualTo("balance 250, entries STARTING_BALANCE:250:250:starting-balance");
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void aRepeatedEventNeverOpensASecondWalletOrCreditsTwice() {
        UUID player = register("bob@example.com");

        // AC-1.5: delivered again in a later transaction, twice within one transaction, and called directly.
        transactions.executeWithoutResult(status -> events.publishEvent(new UserRegistered(player)));
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new UserRegistered(player));
            events.publishEvent(new UserRegistered(player));
        });
        WalletOperationResult again = walletService.openWallet(player);

        assertThat(again.replayed()).isTrue();
        assertThat(again.balanceAfter()).isEqualTo(250);
        assertThat(walletState(player)).isEqualTo("balance 250, entries STARTING_BALANCE:250:250:starting-balance");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isOne();
    }

    @Test
    void aRepeatedEventDoesNotRestoreSpentCoins() {
        UUID player = register("carol@example.com");
        walletService.debit(player, new WalletOperation(WalletTransactionType.TEST_DEBIT, 250, "spend-everything", null));

        transactions.executeWithoutResult(status -> events.publishEvent(new UserRegistered(player)));

        // Recognized by its entry, not by the balance: nothing is credited again.
        assertThat(data.balance(player)).isZero();
        assertThat(data.entries(player)).isEqualTo(2);
        assertThat(data.inconsistentWallets()).isZero();
    }

    @Test
    void concurrentOpeningsOfOneWalletCreateItOnce() throws InterruptedException {
        // A player without a wallet (inserted directly), opened by 10 callers at the same moment.
        UUID player = data.player("dave@example.com");

        List<WalletOperationResult> results = Concurrency.runTogether(10, () -> walletService.openWallet(player));

        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).extracting(WalletOperationResult::balanceAfter).containsOnly(250L);
        assertThat(walletState(player)).isEqualTo("balance 250, entries STARTING_BALANCE:250:250:starting-balance");
    }

    @Test
    void aRolledBackRegistrationLeavesNoAccountWalletOrEntry() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID player = accounts.createAccount(account("erin@example.com")).id();
            // Written in this transaction already (the balance itself is flushed at commit).
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ?""",
                    Integer.class, player)).isOne();
            throw new IllegalStateException("a later step of the registration failed");
        })).hasMessage("a later step of the registration failed");

        assertThat(rowCounts()).isEqualTo("0 users, 0 wallets, 0 entries");
    }

    @Test
    void theEventOutsideATransactionIsAnErrorAndOpensNothing() {
        // MANDATORY: a publication without the registration transaction must not write on its own.
        UUID player = data.player("frank@example.com");

        assertThatThrownBy(() -> events.publishEvent(new UserRegistered(player))).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rowCounts()).isEqualTo("1 users, 0 wallets, 0 entries");
    }

    @Test
    void theAdministratorBootstrapOpensNoWallet() {
        // AC-1.4: createAdministrator publishes no UserRegistered.
        accounts.createAdministrator(account("admin@example.com"));

        assertThat(rowCounts()).isEqualTo("1 users, 0 wallets, 0 entries");
    }

    @Test
    void theStartingBalanceIsNeverATypeAnotherModuleCanCredit() {
        UUID player = register("grace@example.com");

        assertThatThrownBy(() -> walletService.credit(player, new WalletOperation(WalletTransactionType.STARTING_BALANCE, 250, "second-start", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(data.entries(player)).isOne();
    }

    private UUID register(String email) {
        return accounts.createAccount(account(email)).id();
    }

    private static NewAccount account(String email) {
        return new NewAccount(email, "{noop}not-used", "Player", true);
    }

    private String walletState(UUID player) {
        List<String> entries = jdbcTemplate.queryForList("""
                SELECT t.type || ':' || t.amount || ':' || t.balance_after || ':' || t.idempotency_key
                FROM wallet_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE w.user_id = ? ORDER BY t.id""",
                String.class, player);
        return "balance " + data.balance(player) + ", entries " + String.join(", ", entries);
    }

    private String rowCounts() {
        return jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM users) || ' users, ' || (SELECT count(*) FROM wallets) || ' wallets, '
                    || (SELECT count(*) FROM wallet_transactions) || ' entries'""", String.class);
    }
}
