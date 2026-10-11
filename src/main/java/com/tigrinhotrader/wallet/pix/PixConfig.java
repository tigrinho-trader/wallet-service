package com.tigrinhotrader.wallet.pix;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PixProperties.class)
public class PixConfig {

    @Bean
    ProvedorPix provedorPix(RestClient.Builder builder, PixProperties propriedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(15));
        return new AbacatePayProvedorPix(builder.requestFactory(fabrica), propriedades.url(), propriedades.chaveApi());
    }
}
