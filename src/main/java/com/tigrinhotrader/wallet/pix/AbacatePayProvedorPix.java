package com.tigrinhotrader.wallet.pix;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente da API v2 do AbacatePay (checkout transparente Pix):
 * POST /transparents/create, GET /transparents/check?id=, POST /transparents/simulate-payment?id=.
 * Todas as respostas vem no envelope {@code {data, error, success}}.
 */
public class AbacatePayProvedorPix implements ProvedorPix {

    /** O AbacatePay aceita no maximo 500 caracteres de descricao. */
    static final int MAX_DESCRICAO = 500;

    private final RestClient http;
    private final boolean configurado;

    public AbacatePayProvedorPix(RestClient.Builder builder, String baseUrl, String chaveApi) {
        this.configurado = chaveApi != null && !chaveApi.isBlank();
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (configurado ? chaveApi.trim() : ""))
                .build();
    }

    @Override
    public boolean configurado() {
        return configurado;
    }

    @Override
    public CobrancaPix criar(String idExterno, long centavos, String descricao, int expiraEmSegundos) {
        exigirConfigurado();
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("amount", centavos);
        dados.put("expiresIn", expiraEmSegundos);
        dados.put("description", descricao.length() > MAX_DESCRICAO ? descricao.substring(0, MAX_DESCRICAO) : descricao);
        dados.put("externalId", idExterno);
        dados.put("metadata", Map.of("compraId", idExterno));
        JsonNode data = chamar(() -> http.post().uri("/transparents/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "PIX", "data", dados))
                .retrieve().body(JsonNode.class));
        return new CobrancaPix(
                texto(data, "id"),
                data.path("amount").asLong(centavos),
                StatusCobranca.doAbacatePay(texto(data, "status")),
                texto(data, "brCode"),
                texto(data, "brCodeBase64"),
                instante(texto(data, "expiresAt")),
                data.path("devMode").asBoolean(false));
    }

    @Override
    public StatusCobranca consultar(String idCobranca) {
        exigirConfigurado();
        JsonNode data = chamar(() -> http.get()
                .uri(u -> u.path("/transparents/check").queryParam("id", idCobranca).build())
                .retrieve().body(JsonNode.class));
        return StatusCobranca.doAbacatePay(texto(data, "status"));
    }

    @Override
    public void simularPagamento(String idCobranca) {
        exigirConfigurado();
        chamar(() -> http.post()
                .uri(u -> u.path("/transparents/simulate-payment").queryParam("id", idCobranca).build())
                .retrieve().body(JsonNode.class));
    }

    private void exigirConfigurado() {
        if (!configurado) {
            throw new PixIndisponivelException("Pix nao configurado (defina ABACATEPAY_API_KEY)");
        }
    }

    /** Faz a chamada e devolve o {@code data} do envelope, ou falha com 503 explicando o motivo. */
    private JsonNode chamar(Chamada chamada) {
        JsonNode corpo;
        try {
            corpo = chamada.executar();
        } catch (RestClientResponseException e) {
            throw new PixIndisponivelException("AbacatePay respondeu " + e.getStatusCode().value()
                    + ": " + mensagemDe(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new PixIndisponivelException("AbacatePay indisponivel", e);
        }
        if (corpo == null) {
            throw new PixIndisponivelException("AbacatePay respondeu sem corpo");
        }
        JsonNode data = corpo.path("data");
        if (corpo.path("success").isBoolean() && !corpo.path("success").asBoolean() || !data.isObject()) {
            throw new PixIndisponivelException("AbacatePay recusou: " + texto(corpo, "error"));
        }
        return data;
    }

    private static String mensagemDe(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return "sem detalhes";
        }
        int i = corpo.indexOf("\"error\"");
        return i >= 0 ? corpo.substring(i, Math.min(corpo.length(), i + 160)) : "sem detalhes";
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asText();
    }

    private static Instant instante(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface Chamada {
        JsonNode executar();
    }
}
