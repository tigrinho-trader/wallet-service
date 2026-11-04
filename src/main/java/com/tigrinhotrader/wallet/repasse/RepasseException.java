package com.tigrinhotrader.wallet.repasse;

/**
 * O servico externo RESPONDEU recusando a operacao: e certo que ela nao aconteceu.
 * Falha de rede (sem resposta) nao vira esta excecao, porque nesse caso nao da para saber.
 */
public class RepasseException extends RuntimeException {

    public RepasseException(String mensagem) {
        super(mensagem);
    }

    public RepasseException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
