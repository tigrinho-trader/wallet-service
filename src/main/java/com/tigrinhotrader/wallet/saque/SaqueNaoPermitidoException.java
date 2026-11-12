package com.tigrinhotrader.wallet.saque;

/** Saque desligado (sem Asaas de producao): vira 404, como se o saque nao existisse. */
public class SaqueNaoPermitidoException extends RuntimeException {

    public SaqueNaoPermitidoException() {
        super("Nao encontrado");
    }
}
