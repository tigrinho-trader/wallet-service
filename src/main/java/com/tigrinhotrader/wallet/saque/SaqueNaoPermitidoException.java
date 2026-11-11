package com.tigrinhotrader.wallet.saque;

/** Quem nao esta em SAQUE_ADMINS: vira 404, como se o saque nao existisse. */
public class SaqueNaoPermitidoException extends RuntimeException {

    public SaqueNaoPermitidoException() {
        super("Nao encontrado");
    }
}
