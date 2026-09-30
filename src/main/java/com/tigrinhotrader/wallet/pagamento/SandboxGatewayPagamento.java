package com.tigrinhotrader.wallet.pagamento;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simula o fluxo de um provedor (Stripe/Mercado Pago em modo teste): aprova na hora
 * ate um limite por operacao e recusa acima dele, pra dar pra demonstrar os dois caminhos.
 */
@Component
public class SandboxGatewayPagamento implements GatewayPagamento {

    private final BigDecimal limitePorOperacao;

    public SandboxGatewayPagamento(@Value("${wallet.sandbox.limite-por-operacao:5000.00}") BigDecimal limitePorOperacao) {
        this.limitePorOperacao = limitePorOperacao;
    }

    @Override
    public ResultadoPagamento processar(String usuarioId, BigDecimal valor, Operacao operacao) {
        if (valor.compareTo(limitePorOperacao) > 0) {
            return ResultadoPagamento.recusado("Sandbox recusa " + operacao.name().toLowerCase()
                    + " acima de " + limitePorOperacao);
        }
        return ResultadoPagamento.aprovado("sbx_" + UUID.randomUUID());
    }
}
