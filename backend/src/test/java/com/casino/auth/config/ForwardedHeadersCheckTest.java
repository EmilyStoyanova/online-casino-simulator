package com.casino.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Start-up check of the forwarded-header configuration that determines the client IP. */
class ForwardedHeadersCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ForwardedHeadersCheck.class);

    @Test
    void forwardedHeadersAreIgnoredByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("server.forward-headers-strategy=none").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nativeNeedsAnExplicitTrustedProxyList() {
        runner.withPropertyValues("server.forward-headers-strategy=native")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("requires an explicit server.tomcat.remoteip.internal-proxies"));
        runner.withPropertyValues("server.forward-headers-strategy=NATIVE", "server.tomcat.remoteip.internal-proxies=10[.]0[.]0[.]1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void frameworkIsRejectedBecauseItTrustsEveryClient() {
        runner.withPropertyValues("server.forward-headers-strategy=framework", "server.tomcat.remoteip.internal-proxies=10[.]0[.]0[.]1")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("server.forward-headers-strategy=framework is not allowed"));
    }
}
