package com.tigrinhotrader.wallet.repasse;

import com.tigrinhotrader.wallet.pix.AsaasProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RepasseProperties.class)
public class RepasseConfig {

    private static SimpleClientHttpRequestFactory fabrica() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(10));
        fabrica.setReadTimeout(Duration.ofSeconds(30));
        return fabrica;
    }

    @Bean
    AsaasCaixa asaasCaixa(RestClient.Builder builder, AsaasProperties asaas) {
        return new AsaasCaixa(builder.clone().requestFactory(fabrica()), asaas);
    }

    @Bean
    BinanceCaixa binanceCaixa(RestClient.Builder builder,
                              @Value("${wallet.binance.url:https://api.binance.com}") String url,
                              // chave propria do repasse (deposito BRL, compra e saque); sem ela usa a geral
                              @Value("${REPASSE_BINANCE_API_KEY:${BINANCE_API_KEY:}}") String key,
                              @Value("${REPASSE_BINANCE_API_SECRET:${BINANCE_API_SECRET:}}") String secret) {
        return new BinanceCaixa(builder.clone().requestFactory(fabrica()), url, key, secret);
    }

    @Bean
    CarteiraBsc carteiraBsc(RestClient.Builder builder, RepasseProperties props) {
        return new CarteiraBsc(builder.clone().requestFactory(fabrica()), props.bscRpc(), props.carteiraChave());
    }

    @Bean
    DerivCaixa derivCaixa(RestClient.Builder builder, RepasseProperties props) {
        return new DerivCaixa(builder.clone().requestFactory(fabrica()), props.derivUrl(), props.derivToken(), props.derivAppId());
    }
}
