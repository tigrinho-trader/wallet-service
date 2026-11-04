package com.tigrinhotrader.wallet.pix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tigrinhotrader.wallet.pix.ProvedorPix.CobrancaPix;
import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AsaasProvedorPixTest {

    private static final String BASE = "https://api-sandbox.asaas.test/v3";
    /** 12:00 UTC = 09:00 em Brasilia, dia 2026-11-04. */
    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer servidor;
    private AsaasProvedorPix provedor;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        provedor = new AsaasProvedorPix(builder, new AsaasProperties(" $aact_hmlg_teste ", BASE, "", "123.456.789-09"),
                RELOGIO);
    }

    @Test
    void criaClienteNaPrimeiraCompraEDepoisReaproveita() {
        servidor.expect(requestTo(BASE + "/customers?externalReference=tigrinho-trader-pix&limit=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("access_token", "$aact_hmlg_teste"))
                .andRespond(withSuccess("{\"data\":[],\"totalCount\":0}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/customers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"name":"Tigrinho Trader - jogadores","cpfCnpj":"12345678909",
                         "externalReference":"tigrinho-trader-pix","notificationDisabled":true}""", true))
                .andRespond(withSuccess("{\"id\":\"cus_1\"}", MediaType.APPLICATION_JSON));
        esperarCobranca("pay_1");
        esperarCobranca("pay_2");

        provedor.criar("compra-1", 1000, "Flecha Certeira: 10 moedas", 1800);
        provedor.criar("compra-2", 1000, "Flecha Certeira: 10 moedas", 1800);

        servidor.verify();
    }

    @Test
    void criaCobrancaPixEDevolveQrNoFormatoDaLoja() {
        servidor.expect(requestTo(BASE + "/customers?externalReference=tigrinho-trader-pix&limit=1"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"cus_1\"}]}", MediaType.APPLICATION_JSON));
        esperarCobranca("pay_1");

        CobrancaPix c = provedor.criar("compra-1", 1000, "Flecha Certeira: 10 moedas", 1800);

        // QR vale ate 23:59:59 de Brasilia, mas a loja corta em 30 minutos
        assertThat(c).isEqualTo(new CobrancaPix("pay_1", 1000, StatusCobranca.PENDENTE, "00020101abc",
                "data:image/png;base64,iVBORw0KGgo=", Instant.parse("2026-11-04T12:30:00Z"), true));
        servidor.verify();
    }

    private void esperarCobranca(String id) {
        servidor.expect(requestTo(BASE + "/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"customer":"cus_1","billingType":"PIX","value":10.00,"dueDate":"2026-11-04",
                         "description":"Flecha Certeira: 10 moedas"}"""))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"status\":\"PENDING\",\"value\":10.0}",
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/payments/" + id + "/pixQrCode"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"encodedImage":"iVBORw0K\\nGgo=","payload":"00020101abc",
                         "expirationDate":"2026-11-04 23:59:59"}""", MediaType.APPLICATION_JSON));
    }

    @Test
    void consultaStatusEApagadaViraCancelada() {
        servidor.expect(requestTo(BASE + "/payments/pay_1"))
                .andRespond(withSuccess("{\"id\":\"pay_1\",\"status\":\"RECEIVED\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/payments/pay_2"))
                .andRespond(withSuccess("{\"id\":\"pay_2\",\"status\":\"PENDING\",\"deleted\":true}",
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/payments/pay_3")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(provedor.consultar("pay_1")).isEqualTo(StatusCobranca.PAGA);
        assertThat(provedor.consultar("pay_2")).isEqualTo(StatusCobranca.CANCELADA);
        assertThat(provedor.consultar("pay_3")).isEqualTo(StatusCobranca.CANCELADA);
    }

    @ParameterizedTest
    @CsvSource({"PENDING,PENDENTE", "CONFIRMED,PENDENTE", "AWAITING_RISK_ANALYSIS,PENDENTE", "RECEIVED,PAGA",
            "RECEIVED_IN_CASH,PAGA", "OVERDUE,EXPIRADA", "REFUNDED,FALHOU", "CHARGEBACK_REQUESTED,FALHOU",
            "QUALQUER,FALHOU"})
    void traduzStatusDoAsaas(String asaas, StatusCobranca esperado) {
        assertThat(AsaasProvedorPix.status(asaas)).isEqualTo(esperado);
    }

    @Test
    void cancelaSoCobrancaPendente() {
        servidor.expect(requestTo(BASE + "/payments/pay_1"))
                .andRespond(withSuccess("{\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/payments/pay_1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"deleted\":true,\"id\":\"pay_1\"}", MediaType.APPLICATION_JSON));
        servidor.expect(ExpectedCount.once(), requestTo(BASE + "/payments/pay_2"))
                .andRespond(withSuccess("{\"status\":\"RECEIVED\"}", MediaType.APPLICATION_JSON));

        assertThat(provedor.cancelar("pay_1")).isTrue();
        assertThat(provedor.cancelar("pay_2")).isFalse(); // pago: nunca apaga
        servidor.verify();
    }

    @Test
    void simulaPagamentoNoSandbox() {
        servidor.expect(requestTo(BASE + "/sandbox/payment/pay_1/confirm")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"pay_1\",\"status\":\"RECEIVED\"}", MediaType.APPLICATION_JSON));

        provedor.simularPagamento("pay_1");

        servidor.verify();
    }

    @Test
    void erroDoAsaasViraMensagemLegivel() {
        servidor.expect(requestTo(BASE + "/payments/pay_1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"code\":\"invalid_action\",\"description\":\"Cobranca invalida\"}]}"));

        assertThatThrownBy(() -> provedor.consultar("pay_1"))
                .isInstanceOf(PixIndisponivelException.class)
                .hasMessageContaining("400").hasMessageContaining("Cobranca invalida");
    }

    @Test
    void semChaveFicaDesligado() {
        AsaasProvedorPix semChave = new AsaasProvedorPix(RestClient.builder(), new AsaasProperties("", BASE, "", ""),
                RELOGIO);
        assertThat(semChave.configurado()).isFalse();
        assertThatThrownBy(() -> semChave.consultar("x")).isInstanceOf(PixIndisponivelException.class);
    }
}
