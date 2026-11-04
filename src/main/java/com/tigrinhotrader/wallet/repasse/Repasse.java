package com.tigrinhotrader.wallet.repasse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um repasse do saldo do Asaas ate a conta Options da Deriv. Cada etapa e gravada antes e depois de cada
 * operacao com dinheiro, para o servico retomar depois de um reinicio sem nunca repetir um pagamento.
 */
@Entity
@Table(name = "repasses_deriv")
public class Repasse {

    public enum Etapa {
        NOVO("Asaas"),
        PAGANDO(null),                      // pagando o Pix: se parar aqui, nao da para saber se pagou
        PIX_ENVIADO("a caminho da Binance (Pix)"),
        AGUARDANDO_BRL("a caminho da Binance (Pix)"),
        COMPRANDO(null),
        COMPRADO("Binance (USDT)"),
        SACANDO(null),
        AGUARDANDO_CARTEIRA("a caminho da carteira ponte"),
        ENVIANDO(null),
        ENVIADO_DERIV("a caminho da Deriv (rede BSC)"),
        AGUARDANDO_DERIV("carteira USDT da Deriv"),
        TRANSFERINDO("carteira USDT da Deriv"),
        CONCLUIDO("conta Options da Deriv"),
        FALHOU(null);

        /** Onde o dinheiro esta quando o repasse para nesta etapa (null = confira manualmente). */
        final String ondeEstaODinheiro;

        Etapa(String ondeEstaODinheiro) {
            this.ondeEstaODinheiro = ondeEstaODinheiro;
        }

        boolean finalizada() {
            return this == CONCLUIDO || this == FALHOU;
        }
    }

    @Id
    UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    Etapa etapa;

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal valorReais;

    @Column(precision = 19, scale = 8)
    BigDecimal brlAntes;

    String binanceOrdem;
    String pixAsaasId;

    @Column(precision = 19, scale = 8)
    BigDecimal usdtComprado;

    @Column(precision = 19, scale = 8)
    BigDecimal taxaSaque;

    String saqueId;
    String carteiraUsdtAntesWei;
    String txHash;

    @Column(precision = 30, scale = 18)
    BigDecimal derivSaldoAntes;

    @Column(precision = 30, scale = 18)
    BigDecimal usdtEnviado;

    @Column(precision = 19, scale = 2)
    BigDecimal valorOptions;

    @Column(length = 500)
    String erro;

    @Column(nullable = false)
    Instant criadoEm;

    @Column(nullable = false)
    Instant atualizadoEm;

    protected Repasse() {
    }

    Repasse(BigDecimal valorReais, Instant agora) {
        this.id = UUID.randomUUID();
        this.etapa = Etapa.NOVO;
        this.valorReais = valorReais;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    public UUID getId() {
        return id;
    }

    public Etapa getEtapa() {
        return etapa;
    }

    public String getErro() {
        return erro;
    }
}
