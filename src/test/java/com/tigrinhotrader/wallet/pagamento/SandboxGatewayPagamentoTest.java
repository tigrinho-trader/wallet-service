package com.tigrinhotrader.wallet.pagamento;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.wallet.pagamento.GatewayPagamento.Operacao;
import com.tigrinhotrader.wallet.pagamento.GatewayPagamento.ResultadoPagamento;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SandboxGatewayPagamentoTest {

    private final SandboxGatewayPagamento sandbox = new SandboxGatewayPagamento(new BigDecimal("5000"), true);

    @Test
    void aprovaAteOLimite() {
        ResultadoPagamento r = sandbox.processar("u1", new BigDecimal("5000"), Operacao.DEPOSITO);
        assertThat(r.aprovado()).isTrue();
        assertThat(r.idPagamento()).startsWith("sbx_");
        assertThat(r.motivo()).isNull();
    }

    @Test
    void recusaAcimaDoLimite() {
        ResultadoPagamento r = sandbox.processar("u1", new BigDecimal("5000.01"), Operacao.SAQUE);
        assertThat(r.aprovado()).isFalse();
        assertThat(r.idPagamento()).isNull();
        assertThat(r.motivo()).contains("saque").contains("5000");
    }

    @Test
    void desabilitadoRecusaQualquerValor() {
        SandboxGatewayPagamento desligado = new SandboxGatewayPagamento(new BigDecimal("5000"), false);
        for (Operacao operacao : Operacao.values()) {
            ResultadoPagamento r = desligado.processar("u1", new BigDecimal("1"), operacao);
            assertThat(r.aprovado()).isFalse();
            assertThat(r.motivo()).isEqualTo(SandboxGatewayPagamento.MOTIVO_DESABILITADO);
        }
    }
}
