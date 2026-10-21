package com.tigrinhotrader.wallet.servico;

/** A conta ja resgatou este cupom (HTTP 409). */
public class CupomJaUsadoException extends RuntimeException {
    public CupomJaUsadoException() {
        super("Cupom ja usado nesta conta");
    }
}
