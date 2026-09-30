package com.tigrinhotrader.wallet.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Saldo ficticio (fichas) de um jogador. Nunca fica negativo. */
@Entity
@Table(name = "carteiras")
public class Carteira {

    @Id
    private String usuarioId;

    @Version
    private Long versao;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal saldo;

    @Column(nullable = false)
    private Instant criadaEm;

    @Column(nullable = false)
    private Instant atualizadaEm;

    protected Carteira() {
        // JPA
    }

    public Carteira(String usuarioId, BigDecimal saldoInicial, Instant agora) {
        this.usuarioId = usuarioId;
        this.saldo = saldoInicial.setScale(2, RoundingMode.HALF_UP);
        this.criadaEm = agora;
        this.atualizadaEm = agora;
    }

    public void creditar(BigDecimal valor, Instant agora) {
        exigirPositivo(valor);
        saldo = saldo.add(valor).setScale(2, RoundingMode.HALF_UP);
        atualizadaEm = agora;
    }

    public void debitar(BigDecimal valor, Instant agora) {
        exigirPositivo(valor);
        if (saldo.compareTo(valor) < 0) {
            throw new SaldoInsuficienteException(saldo);
        }
        saldo = saldo.subtract(valor).setScale(2, RoundingMode.HALF_UP);
        atualizadaEm = agora;
    }

    /**
     * Aplica lucro/prejuizo de uma rodada. Um prejuizo maior que o saldo
     * (nao deveria acontecer, o trading ja valida) zera a carteira em vez de negativar.
     *
     * @return o valor efetivamente aplicado
     */
    public BigDecimal aplicarResultado(BigDecimal valorLiquido, Instant agora) {
        BigDecimal aplicado = valorLiquido.signum() < 0 && saldo.add(valorLiquido).signum() < 0
                ? saldo.negate()
                : valorLiquido;
        saldo = saldo.add(aplicado).setScale(2, RoundingMode.HALF_UP);
        atualizadaEm = agora;
        return aplicado.setScale(2, RoundingMode.HALF_UP);
    }

    private static void exigirPositivo(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("Valor precisa ser positivo");
        }
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public BigDecimal getSaldo() {
        return saldo;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getAtualizadaEm() {
        return atualizadaEm;
    }
}
