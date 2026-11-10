package com.casino.wallet.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** {@code app.wallet.*}: the defaults (lock timeout, starting balance) and the bounds that stop the start-up. */
class WalletPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(WalletConfig.class);

    @Test
    void theLockTimeoutDefaultsToFiveSeconds() {
        runner.run(context -> assertThat(context.getBean(WalletProperties.class).lockTimeout()).isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void aLockTimeoutWithinTheBoundsIsAccepted() {
        runner.withPropertyValues("app.wallet.lock-timeout=250ms")
                .run(context -> assertThat(context.getBean(WalletProperties.class).lockTimeout()).isEqualTo(Duration.ofMillis(250)));
    }

    @Test
    void aLockTimeoutOutsideTheBoundsStopsTheStartUp() {
        for (String value : new String[] {"0ms", "99ms", "61s", "-1s"}) {
            runner.withPropertyValues("app.wallet.lock-timeout=" + value)
                    .run(context -> assertThat(context).as(value).hasFailed());
        }
    }

    @Test
    void theStartingBalanceDefaultsToOneThousandCoins() {
        runner.run(context -> assertThat(context.getBean(WalletProperties.class).startingBalance()).isEqualTo(1000));
    }

    @Test
    void aStartingBalanceFromOneCoinToTheBalanceLimitIsAccepted() {
        for (long value : new long[] {1, 250, 9_007_199_254_740_991L}) {
            runner.withPropertyValues("app.wallet.starting-balance=" + value)
                    .run(context -> assertThat(context.getBean(WalletProperties.class).startingBalance()).as("%d", value).isEqualTo(value));
        }
    }

    @Test
    void anInvalidStartingBalanceStopsTheStartUp() {
        // AC-1.7: zero, negative, above 2^53 - 1, beyond long, not a whole number.
        for (String value : new String[] {"0", "-1", "9007199254740992", "99999999999999999999", "1000.5", "abc", ""}) {
            runner.withPropertyValues("app.wallet.starting-balance=" + value)
                    .run(context -> assertThat(context).as(value).hasFailed());
        }
    }
}
