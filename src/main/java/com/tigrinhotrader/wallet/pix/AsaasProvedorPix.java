package com.tigrinhotrader.wallet.pix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente da API v3 do Asaas (cobranca Pix dinamica):
 * POST /payments (billingType PIX), GET /payments/{id}/pixQrCode, GET /payments/{id},
 * DELETE /payments/{id} e, so no sandbox, POST /sandbox/payment/{id}/confirm.
 *
 * <p>Toda cobranca do Asaas exige um cliente com CPF/CNPJ. Como o jogo nao pede CPF ao jogador,
 * todas as compras saem em nome de um unico cliente "Tigrinho Trader - jogadores", buscado pelo
 * {@code externalReference} ou criado na primeira compra (com notificacoes do Asaas desligadas).
 */
public class AsaasProvedorPix implements ProvedorPix {

    /** O Asaas aceita no maximo 500 caracteres de descricao. */
    static final int MAX_DESCRICAO = 500;
    static final String REFERENCIA_CLIENTE = "tigrinho-trader-pix";
    static final ZoneId FUSO_ASAAS = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA_ASAAS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final boolean configurado;
    private final boolean sandbox;
    private final String clienteCpfCnpj;
    private final Clock relogio;
    private volatile String clienteId;

    public AsaasProvedorPix(RestClient.Builder builder, AsaasProperties propriedades, Clock relogio) {
        this.configurado = propriedades.configurado();
        this.sandbox = propriedades.url().contains("sandbox");
        this.clienteCpfCnpj = propriedades.clienteCpfCnpj();
        this.clienteId = propriedades.clienteId() == null || propriedades.clienteId().isBlank()
                ? null : propriedades.clienteId().trim();
        this.relogio = relogio;
        this.http = builder
                .baseUrl(propriedades.url())
                .defaultHeader("access_token", configurado ? propriedades.chaveApi().trim() : "")
                // o Asaas exige User-Agent nas contas novas
                .defaultHeader(HttpHeaders.USER_AGENT, "TigrinhoTrader-Wallet/1.0")
                .build();
    }

    @Override
    public boolean configurado() {
        return configurado;
    }

    @Override
    public CobrancaPix criar(String idExterno, long centavos, String descricao, int expiraEmSegundos) {
        exigirConfigurado();
        Instant agora = relogio.instant();
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("customer", cliente());
        dados.put("billingType", "PIX");
        dados.put("value", BigDecimal.valueOf(centavos, 2));
        dados.put("dueDate", LocalDate.ofInstant(agora, FUSO_ASAAS).toString());
        dados.put("description", descricao.length() > MAX_DESCRICAO ? descricao.substring(0, MAX_DESCRICAO) : descricao);
        dados.put("externalReference", idExterno);
        JsonNode pagamento = chamar(() -> http.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dados)
                .retrieve().body(JsonNode.class));
        String id = texto(pagamento, "id");
        if (id == null) {
            throw new PixIndisponivelException("Asaas nao devolveu o id da cobranca");
        }

        JsonNode qr;
        try {
            qr = chamar(() -> http.get().uri("/payments/{id}/pixQrCode", id).retrieve().body(JsonNode.class));
        } catch (PixIndisponivelException e) {
            cancelar(id); // nao deixa cobranca orfa no Asaas
            throw e;
        }

        // O QR do Asaas vale ate o fim do dia (ou meses, com chave Pix); a loja usa a propria validade.
        Instant limite = agora.plusSeconds(expiraEmSegundos);
        Instant expiraQr = instanteAsaas(texto(qr, "expirationDate"));
        Instant expiraEm = expiraQr != null && expiraQr.isBefore(limite) ? expiraQr : limite;
        String imagem = texto(qr, "encodedImage");
        return new CobrancaPix(
                id,
                pagamento.path("value").isNumber()
                        ? pagamento.path("value").decimalValue().movePointRight(2).longValue() : centavos,
                status(texto(pagamento, "status")),
                texto(qr, "payload"),
                imagem == null ? null : "data:image/png;base64," + imagem.replaceAll("\\s", ""),
                expiraEm,
                sandbox);
    }

    @Override
    public StatusCobranca consultar(String idCobranca) {
        exigirConfigurado();
        JsonNode pagamento;
        try {
            pagamento = http.get().uri("/payments/{id}", idCobranca).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return StatusCobranca.CANCELADA;
            }
            throw falha(e);
        } catch (RestClientException e) {
            throw new PixIndisponivelException("Asaas indisponivel", e);
        }
        if (pagamento == null) {
            throw new PixIndisponivelException("Asaas respondeu sem corpo");
        }
        if (pagamento.path("deleted").asBoolean(false)) {
            return StatusCobranca.CANCELADA;
        }
        return status(texto(pagamento, "status"));
    }

    @Override
    public void simularPagamento(String idCobranca) {
        exigirConfigurado();
        if (!sandbox) {
            throw new PixIndisponivelException("Simulacao de pagamento so existe no sandbox do Asaas");
        }
        chamar(() -> http.post().uri("/sandbox/payment/{id}/confirm", idCobranca)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().body(JsonNode.class));
    }

    /**
     * Remove a cobranca no Asaas para o QR parar de aceitar pagamento. So remove se ainda estiver
     * {@code PENDING}: cobranca paga (ou em analise) nunca e apagada. Qualquer erro devolve false.
     */
    @Override
    public boolean cancelar(String idCobranca) {
        if (!configurado) {
            return false;
        }
        try {
            JsonNode pagamento = http.get().uri("/payments/{id}", idCobranca).retrieve().body(JsonNode.class);
            if (pagamento == null || pagamento.path("deleted").asBoolean(false)
                    || !"PENDING".equals(texto(pagamento, "status"))) {
                return false;
            }
            JsonNode resposta = http.delete().uri("/payments/{id}", idCobranca).retrieve().body(JsonNode.class);
            return resposta == null || resposta.path("deleted").asBoolean(true);
        } catch (RestClientException e) {
            return false;
        }
    }

    /**
     * Traduz o status do Asaas. So {@code RECEIVED} (Pix caiu na conta) e {@code RECEIVED_IN_CASH}
     * (baixa manual no painel) contam como pago. {@code CONFIRMED} no Pix e bloqueio cautelar de ate
     * 72h e fica pendente; estorno, chargeback e afins nunca creditam moedas.
     */
    static StatusCobranca status(String status) {
        if (status == null) {
            return StatusCobranca.FALHOU;
        }
        return switch (status) {
            case "PENDING", "CONFIRMED", "AWAITING_RISK_ANALYSIS" -> StatusCobranca.PENDENTE;
            case "RECEIVED", "RECEIVED_IN_CASH" -> StatusCobranca.PAGA;
            case "OVERDUE" -> StatusCobranca.EXPIRADA;
            default -> StatusCobranca.FALHOU;
        };
    }

    /** Id do cliente das cobrancas: o configurado, ou busca/cria um (uma vez por processo). */
    private String cliente() {
        String id = clienteId;
        if (id != null) {
            return id;
        }
        synchronized (this) {
            if (clienteId == null) {
                clienteId = buscarOuCriarCliente();
            }
            return clienteId;
        }
    }

    private String buscarOuCriarCliente() {
        JsonNode lista = chamar(() -> http.get()
                .uri(u -> u.path("/customers").queryParam("externalReference", REFERENCIA_CLIENTE)
                        .queryParam("limit", 1).build())
                .retrieve().body(JsonNode.class));
        String existente = texto(lista.path("data").path(0), "id");
        if (existente != null) {
            return existente;
        }
        String documento = clienteCpfCnpj;
        if (documento == null || documento.isBlank()) {
            documento = texto(chamar(() -> http.get().uri("/myAccount/commercialInfo")
                    .retrieve().body(JsonNode.class)), "cpfCnpj");
        }
        if (documento == null || documento.isBlank()) {
            throw new PixIndisponivelException("Defina ASAAS_CLIENTE_ID ou ASAAS_CLIENTE_CPF_CNPJ");
        }
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("name", "Tigrinho Trader - jogadores");
        dados.put("cpfCnpj", documento.replaceAll("\\D", ""));
        dados.put("externalReference", REFERENCIA_CLIENTE);
        dados.put("notificationDisabled", true);
        String id = texto(chamar(() -> http.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dados)
                .retrieve().body(JsonNode.class)), "id");
        if (id == null) {
            throw new PixIndisponivelException("Asaas nao devolveu o id do cliente");
        }
        return id;
    }

    private void exigirConfigurado() {
        if (!configurado) {
            throw new PixIndisponivelException("Pix nao configurado (defina ASAAS_API_KEY)");
        }
    }

    /** Faz a chamada e devolve o corpo, ou falha com 503 explicando o motivo. */
    private JsonNode chamar(Chamada chamada) {
        JsonNode corpo;
        try {
            corpo = chamada.executar();
        } catch (RestClientResponseException e) {
            throw falha(e);
        } catch (RestClientException e) {
            throw new PixIndisponivelException("Asaas indisponivel", e);
        }
        if (corpo == null || !corpo.isObject()) {
            throw new PixIndisponivelException("Asaas respondeu sem corpo");
        }
        return corpo;
    }

    private static PixIndisponivelException falha(RestClientResponseException e) {
        return new PixIndisponivelException("Asaas respondeu " + e.getStatusCode().value()
                + ": " + mensagemDe(e.getResponseBodyAsString()), e);
    }

    /** Erros do Asaas vem como {@code {"errors":[{"code","description"}]}}. */
    static String mensagemDe(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return "sem detalhes";
        }
        try {
            JsonNode erro = JSON.readTree(corpo).path("errors").path(0);
            String descricao = texto(erro, "description");
            if (descricao != null) {
                return descricao.length() > 200 ? descricao.substring(0, 200) : descricao;
            }
        } catch (Exception ignorado) {
            // corpo nao e JSON
        }
        return "sem detalhes";
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asText();
    }

    /** O Asaas devolve datas como "2026-11-04 23:59:59", no horario de Brasilia. */
    static Instant instanteAsaas(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(texto.trim(), DATA_HORA_ASAAS).atZone(FUSO_ASAAS).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(texto.trim());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    @FunctionalInterface
    private interface Chamada {
        JsonNode executar();
    }
}
