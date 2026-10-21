package com.tigrinhotrader.wallet.pix;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracao da compra de moedas por Pix ({@code wallet.pix.*}).
 *
 * @param chaveApi chave do AbacatePay (env ABACATEPAY_API_KEY). Vazia = Pix desligado. Nunca no repositorio.
 * @param url base da API v2 do AbacatePay
 * @param pacotes quantidades sugeridas na loja (atalhos; o jogador pode escolher outro valor)
 * @param centavosPorMoeda preco de cada moeda em centavos de real (100 = R$ 1 compra 1 moeda)
 * @param expiraEmSegundos validade do QR Code
 * @param maxPendentes quantas cobrancas em aberto um jogador pode ter ao mesmo tempo
 * @param minimoMoedas menor compra aceita (5 moedas = R$ 5; o AbacatePay aceita a partir de R$ 1)
 * @param maximoMoedas maior compra aceita de uma vez
 */
@ConfigurationProperties("wallet.pix")
public record PixProperties(
        @DefaultValue("") String chaveApi,
        @DefaultValue("https://api.abacatepay.com/v2") String url,
        @DefaultValue({"10", "25", "50", "100"}) List<Integer> pacotes,
        @DefaultValue("100") int centavosPorMoeda,
        @DefaultValue("1800") int expiraEmSegundos,
        @DefaultValue("3") int maxPendentes,
        @DefaultValue("5") int minimoMoedas,
        @DefaultValue("2000") int maximoMoedas) {
}
