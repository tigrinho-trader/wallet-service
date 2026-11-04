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
                              @Value("${REPASSE_BINANCE_API_KEY:}") String keyRepasse,
                              @Value("${REPASSE_BINANCE_API_SECRET:}") String secretRepasse,
                              @Value("${BINANCE_API_KEY:}") String keyGeral,
                              @Value("${BINANCE_API_SECRET:}") String secretGeral) {
        // chave propria do repasse; vazia ou ausente = usa a geral (o compose passa as ausentes como vazias)
        return new BinanceCaixa(builder.clone().requestFactory(fabrica()), url,
                preenchido(keyRepasse, keyGeral), preenchido(secretRepasse, secretGeral));
    }

    @Bean
    CarteiraBsc carteiraBsc(RestClient.Builder builder, RepasseProperties props) {
        return new CarteiraBsc(builder.clone().requestFactory(fabrica()), props.bscRpc(), props.carteiraChave());
    }

    @Bean
    DerivCaixa derivCaixa(RestClient.Builder builder, RepasseProperties props,
                          @Value("${DERIV_API_TOKEN:}") String tokenGeral,
                          @Value("${DERIV_APP_ID:}") String appIdGeral) {
        return new DerivCaixa(builder.clone().requestFactory(fabrica()), props.derivUrl(),
                preenchido(props.derivToken(), tokenGeral), preenchido(props.derivAppId(), appIdGeral));
    }

    /** O primeiro valor nao vazio (variavel vazia conta como ausente). */
    static String preenchido(String preferido, String reserva) {
        return preferido != null && !preferido.isBlank() ? preferido.trim() : (reserva == null ? "" : reserva.trim());
    }
}
