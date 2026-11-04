package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Binance para o repasse: deposito BRL via Pix, compra de USDT e saque para a carteira ponte.
 *
 * <p>Dois detalhes validados com dinheiro real: o /sapi/v1/fiat/deposit so aceita os dados em JSON no corpo
 * (form da -1000), e o /sapi/v1/localentity/withdraw/apply so aceita TUDO na URL, com espaco como %20
 * (no corpo da -1022). O questionario da regra de viagem declara "autocustodia do titular": a carteira
 * ponte e do proprio dono, entao a declaracao e verdadeira.
 */
public class BinanceCaixa {

    static final String QUESTIONARIO_AUTOCUSTODIA = "{\"isAddressOwner\":1,\"sendTo\":1,\"declaration\":true}";

    private final RestClient http;
    private final String url;
    private final String key;
    private final String secret;

    public BinanceCaixa(RestClient.Builder builder, String url, String key, String secret) {
        this.http = builder.build();
        this.url = url.replaceAll("/+$", "");
        this.key = key == null ? "" : key.trim();
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean configurado() {
        return !key.isBlank() && !secret.isBlank();
    }

    public record Rede(BigDecimal taxa, BigDecimal minimo) {
    }

    /** Abre um pedido de deposito BRL via Pix; devolve o numero do pedido. */
    public String pedirDepositoPix(BigDecimal valor) {
        JsonNode r = assinado(HttpMethod.POST, "/sapi/v1/fiat/deposit", Map.of(),
                Map.of("currency", "BRL", "apiPaymentMethod", "pix", "amount", valor.toPlainString()));
        String ordem = r.path("data").path("orderId").asText("");
        if (!"000000".equals(r.path("code").asText()) || ordem.isBlank()) {
            throw new RepasseException("Binance nao abriu o deposito Pix: " + r);
        }
        return ordem;
    }

    /** O Pix copia-e-cola do pedido (vem em data.ext.qrCode); null se ainda nao estiver pronto. */
    public String codigoPix(String ordem) {
        return CodigoPix.acharEm(assinado(HttpMethod.GET, "/sapi/v1/fiat/get-order-detail", Map.of("orderNo", ordem), null));
    }

    public BigDecimal livre(String ativo) {
        for (JsonNode saldo : assinado(HttpMethod.GET, "/api/v3/account", Map.of(), null).path("balances")) {
            if (ativo.equals(saldo.path("asset").asText())) {
                return new BigDecimal(saldo.path("free").asText("0"));
            }
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal preco(String simbolo) {
        JsonNode r = http.get().uri(URI.create(url + "/api/v3/ticker/price?symbol=" + simbolo)).retrieve().body(JsonNode.class);
        return new BigDecimal(r.path("price").asText());
    }

    /** Ordem a mercado gastando {@code reais} em USDT; devolve o USDT comprado. */
    public BigDecimal comprarUsdt(BigDecimal reais, String idOrdem) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", "USDTBRL");
        p.put("side", "BUY");
        p.put("type", "MARKET");
        p.put("quoteOrderQty", reais.toPlainString());
        p.put("newClientOrderId", idOrdem);
        p.put("newOrderRespType", "FULL");
        BigDecimal comprado = new BigDecimal(assinado(HttpMethod.POST, "/api/v3/order", p, null).path("executedQty").asText("0"));
        if (comprado.signum() <= 0) {
            throw new RepasseException("A compra de USDT nao executou");
        }
        return comprado;
    }

    public Rede redeBsc(String moeda) {
        for (JsonNode m : assinado(HttpMethod.GET, "/sapi/v1/capital/config/getall", Map.of(), null)) {
            if (!moeda.equals(m.path("coin").asText())) {
                continue;
            }
            for (JsonNode rede : m.path("networkList")) {
                if ("BSC".equals(rede.path("network").asText()) && rede.path("withdrawEnable").asBoolean()) {
                    return new Rede(new BigDecimal(rede.path("withdrawFee").asText()),
                            new BigDecimal(rede.path("withdrawMin").asText()));
                }
            }
        }
        throw new RepasseException("Saque de " + moeda + " pela BSC desligado na Binance");
    }

    /** Saque para uma carteira do proprio titular (a taxa sai de dentro da quantidade). Devolve o id do registro. */
    public String sacarAutocustodia(String moeda, BigDecimal quantidade, String endereco) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("coin", moeda);
        p.put("network", "BSC");
        p.put("address", endereco);
        p.put("amount", quantidade.toPlainString());
        p.put("questionnaire", QUESTIONARIO_AUTOCUSTODIA);
        JsonNode r = assinado(HttpMethod.POST, "/sapi/v1/localentity/withdraw/apply", p, null);
        if (!r.path("accepted").asBoolean(false)) {
            throw new RepasseException("Binance nao aceitou o saque: " + r);
        }
        return r.path("trId").asText();
    }

    /** Assinatura HMAC sobre a query da URL; corpo JSON opcional (so o deposito fiat usa). */
    private JsonNode assinado(HttpMethod metodo, String caminho, Map<String, String> parametros, Map<String, String> corpoJson) {
        Map<String, String> p = new LinkedHashMap<>(parametros);
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("recvWindow", "10000");
        String consulta = p.entrySet().stream().map(e -> e.getKey() + "=" + codificar(e.getValue()))
                .collect(Collectors.joining("&"));
        RestClient.RequestBodySpec req = http.method(metodo)
                .uri(URI.create(url + caminho + "?" + consulta + "&signature=" + assinar(consulta)))
                .header("X-MBX-APIKEY", key);
        if (corpoJson != null) {
            req.contentType(MediaType.APPLICATION_JSON).body(corpoJson);
        }
        try {
            JsonNode corpo = req.retrieve().body(JsonNode.class);
            if (corpo == null) {
                throw new RepasseException("Binance respondeu sem corpo");
            }
            return corpo;
        } catch (RestClientResponseException e) {
            throw new RepasseException("Binance respondeu " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString(), e);
        }
    }

    static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String assinar(String dados) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(dados.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel assinar a requisicao da Binance", e);
        }
    }
}
