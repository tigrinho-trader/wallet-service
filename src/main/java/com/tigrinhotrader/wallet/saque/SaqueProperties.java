package com.tigrinhotrader.wallet.saque;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Saque por Pix do caixa do Asaas ({@code wallet.saque.*}).
 *
 * @param admins            SAQUE_ADMINS: quem pode sacar, por id do login (sub) ou e-mail, separados por virgula.
 *                          Vazio = ninguem (saque desligado)
 * @param limiteDiarioReais SAQUE_LIMITE_DIARIO_REAIS: soma maxima de saques por dia (horario de Brasilia)
 * @param minimoReais       SAQUE_MINIMO_REAIS: menor saque aceito
 */
@ConfigurationProperties("wallet.saque")
public record SaqueProperties(
        @DefaultValue("") String admins,
        @DefaultValue("1000") BigDecimal limiteDiarioReais,
        @DefaultValue("1") BigDecimal minimoReais) {

    Set<String> listaAdmins() {
        return Arrays.stream(admins == null ? new String[0] : admins.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
