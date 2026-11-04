package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.wallet.pix.AsaasProperties;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Saldo do Asaas e pagamento de um Pix copia-e-cola (o deposito na Binance). */
public class AsaasCaixa {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final boolean configurado;

    public AsaasCaixa(RestClient.Builder builder, AsaasProperties propriedades) {
        // no sandbox o dinheiro e de mentira: o repasse real nao roda
        this.configurado = propriedades.configurado() && !propriedades.url().contains("sandbox");
        this.http = builder
                .baseUrl(propriedades.url())
                .defaultHeader("access_token", propriedades.configurado() ? propriedades.chaveApi().trim() : "")
                .defaultHeader(HttpHeaders.USER_AGENT, "TigrinhoTrader-Wallet/1.0")
                .build();
    }

    public boolean configurado() {
        return configurado;
    }

    public BigDecimal saldo() {
        return chamar(() -> http.get().uri("/finance/balance").retrieve().body(JsonNode.class))
                .path("balance").decimalValue();
    }

    public record Pix(String id, String status, String motivo) {
        public boolean concluido() {
            return "DONE".equals(status);
        }

        public boolean recusado() {
            return "REFUSED".equals(status) || "CANCELLED".equals(status);
        }

        public boolean aguardandoAutorizacao() {
            return "AWAITING_CRITICAL_ACTION_AUTHORIZATION".equals(status);
        }
    }

    public Pix pagarPix(String codigo, BigDecimal valor, String descricao) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("qrCode", Map.of("payload", codigo));
        corpo.put("value", valor);
        corpo.put("description", descricao);
        return pix(chamar(() -> http.post().uri("/pix/qrCodes/pay").contentType(MediaType.APPLICATION_JSON)
                .body(corpo).retrieve().body(JsonNode.class)));
    }

    public Pix consultarPix(String id) {
        return pix(chamar(() -> http.get().uri("/pix/transactions/{id}", id).retrieve().body(JsonNode.class)));
    }

    private static Pix pix(JsonNode no) {
        return new Pix(no.path("id").asText(null), no.path("status").asText(null), no.path("refusalReason").asText(null));
    }

    private static JsonNode chamar(java.util.function.Supplier<JsonNode> chamada) {
        try {
            JsonNode corpo = chamada.get();
            if (corpo == null) {
                throw new RepasseException("Asaas respondeu sem corpo");
            }
            return corpo;
        } catch (RestClientResponseException e) {
            throw new RepasseException("Asaas respondeu " + e.getStatusCode().value() + ": "
                    + descricao(e.getResponseBodyAsString()), e);
        }
    }

    private static String descricao(String corpo) {
        try {
            String texto = JSON.readTree(corpo).path("errors").path(0).path("description").asText("");
            return texto.isBlank() ? corpo : texto;
        } catch (Exception e) {
            return corpo == null ? "sem detalhes" : corpo.substring(0, Math.min(corpo.length(), 200));
        }
    }
}
