package com.tigrinhotrader.wallet.dominio;

public enum TipoTransacao {
    BONUS_INICIAL,
    DEPOSITO,
    COMPRA_PIX,
    SAQUE,
    GANHO_RODADA,
    PERDA_RODADA,
    EMPATE_RODADA,
    CUPOM,
    /** Moedas trocadas por um Pix do caixa do Asaas (1 moeda = R$ 1). */
    SAQUE_PIX,
    /** Moedas devolvidas porque o Pix do saque certamente nao saiu. */
    ESTORNO_SAQUE_PIX
}
