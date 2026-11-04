package com.tigrinhotrader.wallet.pix;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracao do Asaas ({@code wallet.pix.asaas.*}). Com chave preenchida, o Asaas substitui o AbacatePay.
 *
 * @param chaveApi chave de API (env ASAAS_API_KEY; $aact_prod_ = producao, $aact_hmlg_ = sandbox). Nunca no repositorio.
 * @param url base da API v3 (producao https://api.asaas.com/v3, sandbox https://api-sandbox.asaas.com/v3)
 * @param clienteId cliente Asaas (cus_...) usado nas cobrancas. Vazio = busca/cria um automaticamente.
 * @param clienteCpfCnpj CPF/CNPJ do cliente criado automaticamente. Vazio = usa o CPF/CNPJ da propria conta.
 */
@ConfigurationProperties("wallet.pix.asaas")
public record AsaasProperties(
        @DefaultValue("") String chaveApi,
        @DefaultValue("https://api.asaas.com/v3") String url,
        @DefaultValue("") String clienteId,
        @DefaultValue("") String clienteCpfCnpj) {

    public boolean configurado() {
        return chaveApi != null && !chaveApi.isBlank();
    }
}
