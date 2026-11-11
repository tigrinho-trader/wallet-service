package com.tigrinhotrader.wallet.repasse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tigrinhotrader.wallet.pix.AsaasProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AsaasCaixaTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final AsaasCaixa asaas = new AsaasCaixa(builder,
            new AsaasProperties("$aact_teste", "https://api.asaas.test/v3", "", ""));

    @Test
    void pixParaChaveVaiComTipoEReferenciaELeOTitular() {
        servidor.expect(requestTo("https://api.asaas.test/v3/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("access_token", "$aact_teste"))
                .andExpect(content().json("{\"value\":25.50,\"operationType\":\"PIX\",\"pixAddressKey\":\"52998224725\","
                        + "\"pixAddressKeyType\":\"CPF\",\"description\":\"Saque\",\"externalReference\":\"S1\"}", true))
                .andRespond(withSuccess("{\"id\":\"tr_1\",\"status\":\"PENDING\",\"authorized\":true,\"transferFee\":0,"
                        + "\"bankAccount\":{\"ownerName\":\"OSCAR R DE S FILHO\"}}", MediaType.APPLICATION_JSON));

        AsaasCaixa.Transferencia t = asaas.transferirPix("52998224725", "CPF", new BigDecimal("25.50"), "Saque", "S1");

        assertThat(t.id()).isEqualTo("tr_1");
        assertThat(t.autorizado()).isTrue();
        assertThat(t.destinatario()).isEqualTo("OSCAR R DE S FILHO");
        assertThat(t.concluida()).isFalse();
        servidor.verify();
    }

    @Test
    void transferenciaSemAutorizacaoEFalhaComMotivo() {
        servidor.expect(requestTo("https://api.asaas.test/v3/transfers/tr_2"))
                .andRespond(withSuccess("{\"id\":\"tr_2\",\"status\":\"PENDING\",\"authorized\":false}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://api.asaas.test/v3/transfers/tr_3"))
                .andRespond(withSuccess("{\"id\":\"tr_3\",\"status\":\"FAILED\",\"failReason\":\"Chave nao encontrada\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(asaas.consultarTransferencia("tr_2").autorizado()).isFalse();
        AsaasCaixa.Transferencia falhou = asaas.consultarTransferencia("tr_3");
        assertThat(falhou.falhou()).isTrue();
        assertThat(falhou.motivo()).isEqualTo("Chave nao encontrada");
    }

    @Test
    void erroDoAsaasViraRepasseExceptionComADescricao() {
        servidor.expect(requestTo("https://api.asaas.test/v3/transfers"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"code\":\"invalid_value\",\"description\":\"Saldo insuficiente\"}]}"));

        assertThatThrownBy(() -> asaas.transferirPix("x@y.com", "EMAIL", BigDecimal.TEN, "Saque", "S2"))
                .isInstanceOf(RepasseException.class).hasMessageContaining("Saldo insuficiente");
    }

    @Test
    void valorLiquidoDaCobranca() {
        servidor.expect(requestTo("https://api.asaas.test/v3/payments/pay_1"))
                .andRespond(withSuccess("{\"id\":\"pay_1\",\"value\":60.00,\"netValue\":59.01}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://api.asaas.test/v3/payments/pay_2"))
                .andRespond(withSuccess("{\"id\":\"pay_2\",\"value\":60.00}", MediaType.APPLICATION_JSON));

        assertThat(asaas.valorLiquido("pay_1")).isEqualByComparingTo("59.01");
        assertThatThrownBy(() -> asaas.valorLiquido("pay_2")).isInstanceOf(RepasseException.class);
    }
}
