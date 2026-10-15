package com.tigrinhotrader.wallet.binance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.StringJoiner;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Cliente mínimo da Binance Link. Nunca expõe chaves ou segredos ao frontend. */
@Component
public class BinanceLinkClient {
    private final RestClient http;
    private final String apiKey;
    private final String apiSecret;
    private final boolean habilitado;
    private final Clock clock;

    public BinanceLinkClient(RestClient.Builder builder,
                             @Value("${wallet.binance.url:https://api.binance.com}") String url,
                             @Value("${BINANCE_API_KEY:}") String apiKey,
                             @Value("${BINANCE_API_SECRET:}") String apiSecret,
                             @Value("${BINANCE_SUBCONTAS_HABILITADO:false}") boolean habilitado,
                             Clock clock) {
        this.http = builder.baseUrl(url).build();
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.habilitado = habilitado;
        this.clock = clock;
    }

    public SubcontaResposta criarSubconta(String tag) {
        exigirConfigurado();
        String query = "tag=" + encode(tag) + "&timestamp=" + clock.millis();
        try {
            return http.post().uri("/sapi/v1/broker/subAccount?" + assinar(query))
                    .header("X-MBX-APIKEY", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve().body(SubcontaResposta.class);
        } catch (RestClientResponseException e) {
            throw traduzirErro(e);
        } catch (RestClientException e) {
            throw new RegraNegocioException("Nao foi possivel comunicar com a Binance agora. Tente novamente.");
        }
    }

    public TransferenciaResposta transferirParaSubconta(String subaccountId, BigDecimal btc, String referencia) {
        exigirConfigurado();
        String query = parametros(Map.of("toId", subaccountId, "clientTranId", referencia,
                "asset", "BTC", "amount", btc.setScale(8, RoundingMode.DOWN).toPlainString(),
                "timestamp", Long.toString(clock.millis())));
        try {
            return http.post().uri("/sapi/v1/broker/transfer?" + assinar(query))
                    .header("X-MBX-APIKEY", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve().body(TransferenciaResposta.class);
        } catch (RestClientResponseException e) {
            throw traduzirErro(e);
        } catch (RestClientException e) {
            throw new RegraNegocioException("Nao foi possivel comunicar com a Binance agora. Tente novamente.");
        }
    }

    public BigDecimal precoBtcEmReais() {
        try {
            Cotacao btcUsdt = http.get().uri("/api/v3/ticker/price?symbol=BTCUSDT")
                    .retrieve().body(Cotacao.class);
            Cotacao usdtBrl = http.get().uri("/api/v3/ticker/price?symbol=USDTBRL")
                    .retrieve().body(Cotacao.class);
            return new BigDecimal(btcUsdt.price()).multiply(new BigDecimal(usdtBrl.price()));
        } catch (RestClientException e) {
            throw new RegraNegocioException("Nao foi possivel consultar a cotacao BTC/BRL agora. Tente novamente.");
        }
    }

    private RegraNegocioException traduzirErro(RestClientResponseException e) {
        String resposta = e.getResponseBodyAsString();
        if (resposta.contains("-2015")) {
            return new RegraNegocioException("A Binance recusou a chave da API. Use uma chave da conta master Binance Link/Broker, habilite a permissao necessaria e autorize o IP deste servidor.");
        }
        return new RegraNegocioException("A Binance recusou o saque. Confira as permissoes da conta master e tente novamente.");
    }

    private void exigirConfigurado() {
        if (!habilitado) throw new RegraNegocioException("Carteiras Binance desabilitadas");
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new RegraNegocioException("BINANCE_API_KEY e BINANCE_API_SECRET nao configuradas");
        }
    }

    private String assinar(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String assinatura = HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
            return query + "&signature=" + assinatura;
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel assinar a requisicao Binance", e);
        }
    }

    private static String parametros(Map<String, String> valores) {
        StringJoiner joiner = new StringJoiner("&");
        valores.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> joiner.add(encode(e.getKey()) + "=" + encode(e.getValue())));
        return joiner.toString();
    }

    private static String encode(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    public record SubcontaResposta(@JsonProperty("subaccountId") String subaccountId,
                                   String email, String tag) { }
    public record TransferenciaResposta(boolean success, String txnId, String clientTranId) { }
    private record Cotacao(String symbol, String price) { }
}
