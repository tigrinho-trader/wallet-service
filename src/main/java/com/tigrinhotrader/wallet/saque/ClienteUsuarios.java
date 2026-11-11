package com.tigrinhotrader.wallet.saque;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pergunta ao user-service se a conta e ADMIN ativa (GET /interno/usuarios/{id}/admin). Fica na rede interna:
 * nenhuma rota do gateway chega em /interno. Qualquer erro conta como "nao e admin" (o saque falha fechado).
 */
public class ClienteUsuarios {

    private static final Logger log = LoggerFactory.getLogger(ClienteUsuarios.class);

    private final RestClient http;

    public ClienteUsuarios(RestClient.Builder builder, String url) {
        this.http = builder.baseUrl(url).build();
    }

    public boolean admin(String usuarioId) {
        try {
            JsonNode resposta = http.get().uri("/interno/usuarios/{id}/admin", usuarioId).retrieve().body(JsonNode.class);
            return resposta != null && resposta.path("admin").asBoolean(false);
        } catch (RestClientException e) {
            log.warn("Nao consegui confirmar no user-service se {} e admin ({}); saque negado", usuarioId, e.toString());
            return false;
        }
    }
}
