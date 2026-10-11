package com.tigrinhotrader.wallet.pix;

/** O provedor Pix nao esta configurado ou nao respondeu direito (HTTP 503). */
public class PixIndisponivelException extends RuntimeException {

    public PixIndisponivelException(String mensagem) {
        super(mensagem);
    }

    public PixIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
