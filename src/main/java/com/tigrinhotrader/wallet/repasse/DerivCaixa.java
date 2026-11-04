package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Deriv (API nova, api.derivws.com): saldo da carteira cripto e transferencia dela para a conta Options real,
 * onde as flechas operam. A conta Options e em USD; a transferencia converte USDT_BSC -> USD na mesma chamada.
 */
public class DerivCaixa {

    static final String MOEDA_CARTEIRA = "USDT_BSC";
    static final BigDecimal MINIMO_TRANSFERENCIA = BigDecimal.ONE;

    private final RestClient http;
    private final boolean configurada;

    public DerivCaixa(RestClient.Builder builder, String url, String token, String appId) {
        this.configurada = token != null && !token.isBlank() && appId != null && !appId.isBlank();
        this.http = builder.baseUrl(url)
                .defaultHeader("Authorization", "Bearer " + (token == null ? "" : token.trim()))
                .defaultHeader("Deriv-App-ID", appId == null ? "" : appId.trim())
                .build();
    }

    public boolean configurada() {
        return configurada;
    }

    public record Carteira(String id, BigDecimal saldo) {
    }

    public Carteira carteiraPrincipal() {
        for (JsonNode c : chamar(() -> http.get().uri("/wallet/v1/wallets").retrieve().body(JsonNode.class))
                .path("data").path("wallets")) {
            if ("main".equals(c.path("type").asText())) {
                String saldo = c.path("balances").path(MOEDA_CARTEIRA).path("balance").asText("0");
                return new Carteira(c.path("wallet_id").asText(), new BigDecimal(saldo));
            }
        }
        throw new RepasseException("Carteira principal da Deriv nao encontrada");
    }

    JsonNode contaOptionsReal() {
        for (JsonNode conta : chamar(() -> http.get().uri("/trading/v1/options/accounts").retrieve().body(JsonNode.class))
                .path("data")) {
            if ("real".equals(conta.path("account_type").asText()) && "active".equals(conta.path("status").asText())) {
                return conta;
            }
        }
        throw new RepasseException("Conta Options real ativa nao encontrada na Deriv");
    }

    /**
     * Passa todo o saldo da carteira para a conta Options real. O {@code requestId} torna a chamada idempotente:
     * repetir com o mesmo id nunca transfere duas vezes. Devolve o valor pedido na moeda da conta (zero se nada).
     */
    public BigDecimal transferirParaOptions(UUID requestId) {
        Carteira carteira = carteiraPrincipal();
        if (carteira.saldo().compareTo(MINIMO_TRANSFERENCIA) < 0) {
            return BigDecimal.ZERO;
        }
        JsonNode conta = contaOptionsReal();
        String moeda = conta.path("currency").asText();
        JsonNode cotacao = chamar(() -> http.get()
                .uri("/wallet/v1/exchange-rate?source_currency={de}&destination_currency={para}", MOEDA_CARTEIRA, moeda)
                .retrieve().body(JsonNode.class)).path("data");
        // arredondado para baixo: cabe no saldo qualquer que seja a moeda em que a Deriv conta o "amount"
        BigDecimal valor = carteira.saldo().multiply(new BigDecimal(cotacao.path("exchange_rate").asText()))
                .setScale(2, RoundingMode.DOWN);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("wallet_id", carteira.id());
        corpo.put("platform_account_id", conta.path("account_id").asText());
        corpo.put("amount", valor.toPlainString());
        corpo.put("currency", moeda);
        corpo.put("direction", "from_wallet");
        corpo.put("platform_name", "options");
        corpo.put("request_id", requestId.toString());
        corpo.put("wallet_currency", MOEDA_CARTEIRA);
        corpo.put("exchange_rate", cotacao.path("exchange_rate").asText());
        corpo.put("rate_token", cotacao.path("rate_token").asText());
        corpo.put("description", "Repasse automatico: carteira -> Options");
        try {
            chamar(() -> http.post().uri("/wallet/v1/transfers/platforms").contentType(MediaType.APPLICATION_JSON)
                    .body(corpo).retrieve().body(JsonNode.class));
        } catch (RepasseException e) {
            if (e.getMessage().contains("DuplicateRequestID")) {
                return valor; // ja feita numa tentativa anterior
            }
            throw e;
        }
        return valor;
    }

    private static JsonNode chamar(java.util.function.Supplier<JsonNode> chamada) {
        try {
            JsonNode corpo = chamada.get();
            if (corpo == null) {
                throw new RepasseException("Deriv respondeu sem corpo");
            }
            return corpo;
        } catch (RestClientResponseException e) {
            throw new RepasseException("Deriv respondeu " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString(), e);
        }
    }
}
