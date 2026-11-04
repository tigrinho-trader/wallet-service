package com.tigrinhotrader.wallet.pix;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({PixProperties.class, AsaasProperties.class})
public class PixConfig {

    /** Com ASAAS_API_KEY definida usa o Asaas; senao cai no AbacatePay (legado). */
    @Bean
    ProvedorPix provedorPix(RestClient.Builder builder, PixProperties propriedades, AsaasProperties asaas,
                            Clock relogio) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(15));
        if (asaas.configurado()) {
            return new AsaasProvedorPix(builder.requestFactory(fabrica), asaas, relogio);
        }
        return new AbacatePayProvedorPix(builder.requestFactory(fabrica), propriedades.url(), propriedades.chaveApi());
    }
}
