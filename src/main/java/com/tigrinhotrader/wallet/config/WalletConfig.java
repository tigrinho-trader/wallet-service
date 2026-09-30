package com.tigrinhotrader.wallet.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
