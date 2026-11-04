package com.tigrinhotrader.wallet.pix;

import java.time.Instant;

/**
 * Porta para o provedor de cobrancas Pix (hoje AbacatePay). O servico de compras so conhece esta
 * interface, entao os testes trocam o provedor por um falso.
 */
public interface ProvedorPix {

    /** Tem chave de API configurada? Sem ela a compra por Pix fica desligada. */
    boolean configurado();

    /**
     * Cria a cobranca.
     *
     * @param idExterno id da compra no nosso sistema (idempotencia no provedor)
     */
    CobrancaPix criar(String idExterno, long centavos, String descricao, int expiraEmSegundos);

    /** Status atual da cobranca no provedor. */
    StatusCobranca consultar(String idCobranca);

    /** Simula o pagamento (so funciona em modo de desenvolvimento do provedor). */
    void simularPagamento(String idCobranca);

    /**
     * Cancela no provedor uma cobranca ainda nao paga, para o QR parar de aceitar pagamento.
     * Devolve true se cancelou. Provedores que expiram sozinhos (AbacatePay) nao precisam.
     */
    default boolean cancelar(String idCobranca) {
        return false;
    }

    record CobrancaPix(String id, long centavos, StatusCobranca status, String brCode, String brCodeBase64,
                       Instant expiraEm, boolean devMode) {
    }

    enum StatusCobranca {
        PENDENTE, PAGA, EXPIRADA, CANCELADA, FALHOU;

        /**
         * Traduz o status do AbacatePay. So {@code PAID} conta como pago: estorno, disputa e afins
         * nunca creditam moedas.
         */
        public static StatusCobranca doAbacatePay(String status) {
            if (status == null) {
                return FALHOU;
            }
            return switch (status) {
                case "PENDING" -> PENDENTE;
                case "PAID" -> PAGA;
                case "EXPIRED" -> EXPIRADA;
                case "CANCELLED" -> CANCELADA;
                default -> FALHOU;
            };
        }
    }
}
