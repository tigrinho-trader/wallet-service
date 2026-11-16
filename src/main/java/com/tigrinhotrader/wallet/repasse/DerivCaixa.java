package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Deriv (API nova, api.derivws.com): saldo da carteira cripto e transferencia dela para a conta Options real,
 * onde as flechas operam. A conta Options e em USD; a transferencia converte USDT -> USD na mesma chamada.
 * <p>
 * O USDT enviado pela rede BSC caia no saldo {@code USDT_BSC} da carteira principal; hoje a Deriv credita em
 * {@code USDT}. Por isso conta e transfere todo saldo de USDT da carteira (USDT, USDT_BSC ou outra rede).
 */
public class DerivCaixa {

    /** O saldo antigo dos depositos BSC; a transferencia dele usa o request_id sem sufixo (o de antes). */
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

    /** Carteira principal com o saldo de cada moeda USDT ({@code USDT}, {@code USDT_BSC}...). */
    public record Carteira(String id, Map<String, BigDecimal> saldos) {

        public Carteira(String id, BigDecimal saldo) {
            this(id, Map.of(MOEDA_CARTEIRA, saldo));
        }

        /** Todo o USDT da carteira, somando as moedas. */
        public BigDecimal saldo() {
            return saldos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** USDT de qualquer rede: "USDT", "USDT_BSC", "USDT_TRC20"... */
    static boolean moedaUsdt(String moeda) {
        return "USDT".equals(moeda) || moeda.startsWith("USDT_");
    }

    public Carteira carteiraPrincipal() {
        for (JsonNode c : chamar(() -> http.get().uri("/wallet/v1/wallets").retrieve().body(JsonNode.class))
                .path("data").path("wallets")) {
            if ("main".equals(c.path("type").asText())) {
                Map<String, BigDecimal> saldos = new LinkedHashMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> it = c.path("balances").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> moeda = it.next();
                    if (moedaUsdt(moeda.getKey())) {
                        saldos.put(moeda.getKey(), new BigDecimal(moeda.getValue().path("balance").asText("0")));
                    }
                }
                return new Carteira(c.path("wallet_id").asText(), saldos);
            }
        }
        throw new RepasseException("Carteira principal da Deriv nao encontrada");
    }

    /** Saldo da conta Options real (onde as flechas operam), na moeda dela (USD). */
    public record SaldoOptions(String conta, BigDecimal saldo, String moeda) {
    }

    public SaldoOptions saldoOptions() {
        JsonNode conta = contaOptionsReal();
        return new SaldoOptions(conta.path("account_id").asText(), new BigDecimal(conta.path("balance").asText("0")),
                conta.path("currency").asText("USD"));
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
     * Passa todo o USDT da carteira para a conta Options real: uma transferencia por moeda com saldo (USDT,
     * USDT_BSC...). O {@code requestId} torna a chamada idempotente: repetir com o mesmo id nunca transfere duas
     * vezes (cada moeda usa um id proprio derivado dele). Devolve o valor pedido na moeda da conta (zero se nada).
     */
    public BigDecimal transferirParaOptions(UUID requestId) {
        Carteira carteira = carteiraPrincipal();
        JsonNode conta = null;
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> saldo : carteira.saldos().entrySet()) {
            if (saldo.getValue().compareTo(MINIMO_TRANSFERENCIA) < 0) {
                continue;
            }
            if (conta == null) {
                conta = contaOptionsReal();
            }
            total = total.add(transferir(carteira.id(), saldo.getKey(), saldo.getValue(), conta,
                    idDaMoeda(requestId, saldo.getKey())));
        }
        return total;
    }

    /** O id de antes para USDT_BSC (repetir um repasse antigo continua idempotente); um id derivado para as outras. */
    static UUID idDaMoeda(UUID requestId, String moedaCarteira) {
        return MOEDA_CARTEIRA.equals(moedaCarteira) ? requestId
                : UUID.nameUUIDFromBytes((requestId + "-" + moedaCarteira).getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal transferir(String carteiraId, String moedaCarteira, BigDecimal saldo, JsonNode conta,
                                  UUID requestId) {
        String moeda = conta.path("currency").asText();
        JsonNode cotacao = chamar(() -> http.get()
                .uri("/wallet/v1/exchange-rate?source_currency={de}&destination_currency={para}", moedaCarteira, moeda)
                .retrieve().body(JsonNode.class)).path("data");
        // arredondado para baixo: cabe no saldo qualquer que seja a moeda em que a Deriv conta o "amount"
        BigDecimal valor = saldo.multiply(new BigDecimal(cotacao.path("exchange_rate").asText()))
                .setScale(2, RoundingMode.DOWN);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("wallet_id", carteiraId);
        corpo.put("platform_account_id", conta.path("account_id").asText());
        corpo.put("amount", valor.toPlainString());
        corpo.put("currency", moeda);
        corpo.put("direction", "from_wallet");
        corpo.put("platform_name", "options");
        corpo.put("request_id", requestId.toString());
        corpo.put("wallet_currency", moedaCarteira);
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
