package com.tigrinhotrader.wallet.pagamento;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simula o fluxo de um provedor (Stripe/Mercado Pago em modo teste): aprova na hora
 * ate um limite por operacao e recusa acima dele, pra dar pra demonstrar os dois caminhos.
 * Com {@code wallet.sandbox.habilitado=false} recusa tudo: e assim que se desliga o dinheiro
 * "de mentira" antes de trocar este adaptador por um provedor real (Pix).
 */
@Component
public class SandboxGatewayPagamento implements GatewayPagamento {

    static final String MOTIVO_DESABILITADO = "Pagamentos desabilitados";

    private final BigDecimal limitePorOperacao;
    private final boolean habilitado;

    public SandboxGatewayPagamento(@Value("${wallet.sandbox.limite-por-operacao:5000.00}") BigDecimal limitePorOperacao,
                                   @Value("${wallet.sandbox.habilitado:true}") boolean habilitado) {
        this.limitePorOperacao = limitePorOperacao;
        this.habilitado = habilitado;
    }

    @Override
    public ResultadoPagamento processar(String usuarioId, BigDecimal valor, Operacao operacao) {
        if (!habilitado) {
            return ResultadoPagamento.recusado(MOTIVO_DESABILITADO);
        }
        if (valor.compareTo(limitePorOperacao) > 0) {
            return ResultadoPagamento.recusado("Sandbox recusa " + operacao.name().toLowerCase()
                    + " acima de " + limitePorOperacao);
        }
        return ResultadoPagamento.aprovado("sbx_" + UUID.randomUUID());
    }
}
