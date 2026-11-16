package com.tigrinhotrader.wallet.painel;

/**
 * Carteira de poupanca do super-admin: recebe a parte poupada dos ganhos das flechas de mercado (o trading-service
 * manda pelo mesmo evento ordem.executada, com este usuarioId e tipo {@value #TIPO_EVENTO}). Nenhum login de jogador
 * chega nela (o id do jogador vem do token) e ela nasce sem bonus; so o painel mostra o saldo.
 */
public final class Poupanca {

    public static final String CARTEIRA = "poupanca-super-admin";
    public static final String TIPO_EVENTO = "POUPANCA";

    private Poupanca() {
    }
}
