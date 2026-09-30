package com.tigrinhotrader.wallet.pagamento;

import java.math.BigDecimal;

/**
 * Porta para o provedor de pagamento. Hoje so existe a implementacao sandbox:
 * o projeto nao movimenta dinheiro real (ver docs-arquitetura, regulacao Bacen/CVM).
 */
public interface GatewayPagamento {

    ResultadoPagamento processar(String usuarioId, BigDecimal valor, Operacao operacao);

    enum Operacao { DEPOSITO, SAQUE }

    record ResultadoPagamento(boolean aprovado, String idPagamento, String motivo) {

        public static ResultadoPagamento aprovado(String id) {
            return new ResultadoPagamento(true, id, null);
        }

        public static ResultadoPagamento recusado(String motivo) {
            return new ResultadoPagamento(false, null, motivo);
        }
    }
}
