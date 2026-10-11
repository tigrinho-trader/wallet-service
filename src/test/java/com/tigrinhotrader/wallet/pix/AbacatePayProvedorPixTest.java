package com.tigrinhotrader.wallet.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tigrinhotrader.wallet.pix.ProvedorPix.CobrancaPix;
import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AbacatePayProvedorPixTest {

    private static final String BASE = "https://api.abacatepay.test/v2";

    private MockRestServiceServer servidor;
    private AbacatePayProvedorPix provedor;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        provedor = new AbacatePayProvedorPix(builder, BASE, " abc_dev_teste ");
    }

    @Test
    void criaACobrancaNoFormatoDaApiV2() {
        servidor.expect(requestTo(BASE + "/transparents/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer abc_dev_teste"))
                .andExpect(content().json("""
                        {"method":"PIX","data":{"amount":500,"expiresIn":1800,
                         "description":"Flecha Certeira: 500 moedas","externalId":"compra-1",
                         "metadata":{"compraId":"compra-1"}}}""", true))
                .andRespond(withSuccess("""
                        {"data":{"id":"pix_char_1","amount":500,"status":"PENDING","devMode":true,
                         "brCode":"000201abc","brCodeBase64":"data:image/png;base64,AAA",
                         "expiresAt":"2026-10-11T12:30:00.000Z"},"success":true,"error":null}""",
                        MediaType.APPLICATION_JSON));

        CobrancaPix c = provedor.criar("compra-1", 500, "Flecha Certeira: 500 moedas", 1800);

        assertThat(c).isEqualTo(new CobrancaPix("pix_char_1", 500, StatusCobranca.PENDENTE, "000201abc",
                "data:image/png;base64,AAA", Instant.parse("2026-10-11T12:30:00Z"), true));
        servidor.verify();
    }

    @Test
    void cortaADescricaoNoLimiteDoAbacatePayEToleraCamposAusentes() {
        String longa = "x".repeat(AbacatePayProvedorPix.MAX_DESCRICAO + 50);
        servidor.expect(requestTo(BASE + "/transparents/create"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"description\":\"" + "x".repeat(AbacatePayProvedorPix.MAX_DESCRICAO) + "\"")))
                .andRespond(withSuccess("{\"data\":{\"id\":\"pix_char_2\",\"expiresAt\":\"ontem\",\"brCode\":null},\"success\":true}",
                        MediaType.APPLICATION_JSON));

        CobrancaPix c = provedor.criar("c", 700, longa, 60);

        assertThat(c.centavos()).isEqualTo(700);
        assertThat(c.status()).isEqualTo(StatusCobranca.FALHOU);
        assertThat(c.brCode()).isNull();
        assertThat(c.expiraEm()).isNull();
        assertThat(c.devMode()).isFalse();
    }

    @Test
    void consultaESimulaPeloIdNaQueryString() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=pix_char_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"id\":\"pix_char_1\",\"status\":\"PAID\"},\"success\":true}",
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/transparents/simulate-payment?id=pix_char_1"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{\"id\":\"pix_char_1\",\"status\":\"PAID\"},\"success\":true}",
                        MediaType.APPLICATION_JSON));

        assertThat(provedor.consultar("pix_char_1")).isEqualTo(StatusCobranca.PAGA);
        provedor.simularPagamento("pix_char_1");
        servidor.verify();
    }

    @Test
    void envelopeSemSucessoViraIndisponivel() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=x"))
                .andRespond(withSuccess("{\"data\":null,\"success\":false,\"error\":\"Cobranca nao encontrada\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provedor.consultar("x"))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessageContaining("Cobranca nao encontrada");
    }

    @Test
    void dataQueNaoEObjetoViraIndisponivel() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=x"))
                .andRespond(withSuccess("{\"error\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provedor.consultar("x")).isInstanceOf(PixIndisponivelException.class);
    }

    @Test
    void respostaHttpDeErroMostraOMotivo() {
        servidor.expect(requestTo(BASE + "/transparents/create"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Token de autenticacao invalido\"}"));

        assertThatThrownBy(() -> provedor.criar("c", 100, "d", 60))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Token de autenticacao invalido");
    }

    @Test
    void erroHttpSemCorpo() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=x")).andRespond(withServerError());

        assertThatThrownBy(() -> provedor.consultar("x"))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("sem detalhes");
    }

    @Test
    void erroHttpComCorpoSemCampoError() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=x"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>gateway</html>"));

        assertThatThrownBy(() -> provedor.consultar("x")).hasMessageContaining("sem detalhes");
    }

    @Test
    void falhaDeRedeViraIndisponivel() {
        servidor.expect(requestTo(BASE + "/transparents/check?id=x")).andRespond(req -> {
            throw new IOException("sem rede");
        });

        assertThatThrownBy(() -> provedor.consultar("x"))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessage("AbacatePay indisponivel");
    }

    @Test
    void respostaSemCorpo() {
        servidor.expect(requestTo(BASE + "/transparents/simulate-payment?id=x"))
                .andRespond(withStatus(HttpStatus.OK));

        assertThatThrownBy(() -> provedor.simularPagamento("x"))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessageContaining("sem corpo");
    }

    @Test
    void semChaveNaoChamaNada() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer semChamadas = MockRestServiceServer.bindTo(builder).build();
        AbacatePayProvedorPix desligado = new AbacatePayProvedorPix(builder, BASE, "  ");

        assertThat(desligado.configurado()).isFalse();
        assertThat(new AbacatePayProvedorPix(RestClient.builder(), BASE, null).configurado()).isFalse();
        assertThatThrownBy(() -> desligado.criar("c", 1, "d", 1)).hasMessageContaining("ABACATEPAY_API_KEY");
        assertThatThrownBy(() -> desligado.consultar("x")).isInstanceOf(PixIndisponivelException.class);
        assertThatThrownBy(() -> desligado.simularPagamento("x")).isInstanceOf(PixIndisponivelException.class);
        semChamadas.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, PENDENTE", "PAID, PAGA", "EXPIRED, EXPIRADA", "CANCELLED, CANCELADA",
            "REFUNDED, FALHOU", "UNDER_DISPUTE, FALHOU", "APPROVED, FALHOU", "QUALQUER, FALHOU"})
    void traduzOsStatusDoAbacatePay(String abacate, StatusCobranca esperado) {
        assertThat(StatusCobranca.doAbacatePay(abacate)).isEqualTo(esperado);
    }

    @Test
    void statusNuloEFalha() {
        assertThat(StatusCobranca.doAbacatePay(null)).isEqualTo(StatusCobranca.FALHOU);
    }
}
