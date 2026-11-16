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
 * @param minimoMoedas menor compra aceita por padrao (30 moedas = R$ 30: abaixo disso o repasse para a corretora fica
 *                     juntando ate o saque minimo da Binance); o painel do super-admin pode mudar
 * @param maximoMoedas maior compra aceita de uma vez
 */
@ConfigurationProperties("wallet.pix")
public record PixProperties(
        @DefaultValue("") String chaveApi,
        @DefaultValue("https://api.abacatepay.com/v2") String url,
        @DefaultValue({"30", "50", "100", "200"}) List<Integer> pacotes,
        @DefaultValue("100") int centavosPorMoeda,
        @DefaultValue("1800") int expiraEmSegundos,
        @DefaultValue("30") int minimoMoedas,
        @DefaultValue("2000") int maximoMoedas) {
}
