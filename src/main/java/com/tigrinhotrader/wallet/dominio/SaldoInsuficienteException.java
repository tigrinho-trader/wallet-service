package com.tigrinhotrader.wallet.dominio;

import java.math.BigDecimal;

public class SaldoInsuficienteException extends RuntimeException {
    public SaldoInsuficienteException(BigDecimal saldoAtual) {
        super("Saldo insuficiente: saldo atual " + saldoAtual);
    }
}
