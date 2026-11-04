package com.tigrinhotrader.wallet.repasse;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Repasse automatico Asaas -> Binance -> carteira ponte -> Deriv ({@code wallet.repasse.*}).
 *
 * @param habilitado     REPASSE_DERIV_HABILITADO; false deixa o dinheiro no Asaas
 * @param minimoReais    so inicia um repasse com pelo menos este saldo no Asaas
 * @param carteiraChave  CARTEIRA_PONTE_CHAVE: chave privada da carteira ponte (BSC). Nunca no repositorio.
 * @param derivEndereco  DERIV_USDT_BSC_ADDRESS: endereco de deposito USDT (BEP20) da Deriv
 * @param derivToken     DERIV_API_TOKEN: precisa da permissao "payment" (carteira e transferencias)
 * @param prazoMaximo    um repasse parado ha mais tempo que isso e marcado como falho
 * @param esperaAposFalha depois de uma falha, espera isso antes de iniciar outro repasse
 */
@ConfigurationProperties("wallet.repasse")
public record RepasseProperties(
        @DefaultValue("true") boolean habilitado,
        @DefaultValue("30") BigDecimal minimoReais,
        @DefaultValue("") String carteiraChave,
        @DefaultValue("") String derivEndereco,
        @DefaultValue("https://bsc-dataseed.bnbchain.org") String bscRpc,
        @DefaultValue("https://api.derivws.com") String derivUrl,
        @DefaultValue("") String derivToken,
        @DefaultValue("") String derivAppId,
        @DefaultValue("3h") Duration prazoMaximo,
        @DefaultValue("6h") Duration esperaAposFalha) {
}
