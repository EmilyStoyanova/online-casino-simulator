package com.casino.wallet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration of the {@code wallet} module. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WalletProperties.class)
public class WalletConfig {
}
