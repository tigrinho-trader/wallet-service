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
 * @param centavosPorMoeda preco de cada moeda em centavos de real (10 = R$ 1 compra 10 moedas)
 * @param expiraEmSegundos validade do QR Code
 * @param maxPendentes quantas cobrancas em aberto um jogador pode ter ao mesmo tempo
 * @param minimoMoedas menor compra aceita (10 moedas = R$ 1, o minimo do AbacatePay)
 * @param maximoMoedas maior compra aceita de uma vez
 */
@ConfigurationProperties("wallet.pix")
public record PixProperties(
        @DefaultValue("") String chaveApi,
        @DefaultValue("https://api.abacatepay.com/v2") String url,
        @DefaultValue({"100", "500", "1000", "5000"}) List<Integer> pacotes,
        @DefaultValue("10") int centavosPorMoeda,
        @DefaultValue("1800") int expiraEmSegundos,
        @DefaultValue("3") int maxPendentes,
        @DefaultValue("10") int minimoMoedas,
        @DefaultValue("10000") int maximoMoedas) {
}
