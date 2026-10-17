package com.tigrinhotrader.wallet.binance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.StringJoiner;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Cliente da API padrão de subcontas Binance. Mantém o cliente Link/Broker separado. */
@Component
public class BinanceStandardClient {
    private final RestClient http;
    private final String apiKey;
    private final String apiSecret;
    private final boolean habilitado;
    private final Clock clock;

    public BinanceStandardClient(RestClient.Builder builder,
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

    public SubcontaResposta criarSubconta(String nome) {
        exigirConfigurado();
        String query = parametros(Map.of("subAccountString", nome, "timestamp", Long.toString(clock.millis())));
        try {
            return http.post().uri("/sapi/v1/sub-account/virtualSubAccount?" + assinar(query))
                    .header("X-MBX-APIKEY", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve().body(SubcontaResposta.class);
        } catch (RestClientResponseException e) {
            throw traduzirErro(e);
        } catch (RestClientException e) {
            throw new RegraNegocioException("Nao foi possivel comunicar com a Binance agora. Tente novamente.");
        }
    }

    public TransferenciaResposta transferirParaSubconta(String email, BigDecimal btc, String referencia) {
        exigirConfigurado();
        String query = parametros(Map.of(
                "fromEmail", "",
                "toEmail", email,
                "fromAccountType", "SPOT",
                "toAccountType", "SPOT",
                "asset", "BTC",
                "amount", btc.setScale(8, java.math.RoundingMode.DOWN).toPlainString(),
                "clientTranId", referencia,
                "timestamp", Long.toString(clock.millis())));
        try {
            return http.post().uri("/sapi/v1/sub-account/universalTransfer?" + assinar(query))
                    .header("X-MBX-APIKEY", apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve().body(TransferenciaResposta.class);
        } catch (RestClientResponseException e) {
            throw traduzirErro(e);
        } catch (RestClientException e) {
            throw new RegraNegocioException("Nao foi possivel comunicar com a Binance agora. Tente novamente.");
        }
    }

    private RegraNegocioException traduzirErro(RestClientResponseException e) {
        String corpo = e.getResponseBodyAsString();
        if (corpo.contains("-2015")) {
            return new RegraNegocioException("A Binance recusou a chave. Verifique Trade, Permits Universal Transfer e o IP autorizado.");
        }
        if (corpo.contains("-1002") || corpo.contains("-2014")) {
            return new RegraNegocioException("A Binance recusou a permissao da API para subcontas ou transferencia interna.");
        }
        return new RegraNegocioException("A Binance recusou o saque. Confira as permissoes e tente novamente.");
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
            return query + "&signature=" + HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
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

    public record SubcontaResposta(@JsonProperty("email") String email) { }
    public record TransferenciaResposta(@JsonProperty("tranId") String tranId) { }
}
