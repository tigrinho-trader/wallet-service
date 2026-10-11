package com.tigrinhotrader.wallet.pix;

import java.util.UUID;

/** Compra que nao existe ou e de outro jogador (HTTP 404). */
public class CompraNaoEncontradaException extends RuntimeException {

    public CompraNaoEncontradaException(UUID id) {
        super("Compra " + id + " nao encontrada");
    }
}
