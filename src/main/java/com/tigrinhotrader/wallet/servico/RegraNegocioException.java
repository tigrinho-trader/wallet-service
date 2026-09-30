package com.tigrinhotrader.wallet.servico;

/** Pedido que quebra uma regra de negocio (HTTP 422). */
public class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String mensagem) {
        super(mensagem);
    }
}
