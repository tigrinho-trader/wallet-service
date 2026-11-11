package com.tigrinhotrader.wallet.saque;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class SaqueConfig {

    /** user-service na rede interna (USER_URL), para saber quem e ADMIN. */
    @Bean
    ClienteUsuarios clienteUsuarios(RestClient.Builder builder, @Value("${wallet.usuarios.url:http://localhost:8085}") String url) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(10));
        return new ClienteUsuarios(builder.clone().requestFactory(fabrica), url);
    }
}
