package com.tigrinhotrader.wallet.saque;

/** Conta que nao e ADMIN: vira 404, como se o saque nao existisse. */
public class SaqueNaoPermitidoException extends RuntimeException {

    public SaqueNaoPermitidoException() {
        super("Nao encontrado");
    }
}
