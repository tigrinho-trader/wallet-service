package com.tigrinhotrader.wallet.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Linha do extrato. {@code referencia} e unica: e o ordemId (rodadas) ou o id do
 * pagamento sandbox, e garante que o mesmo evento nunca e aplicado duas vezes.
 */
@Entity
@Table(name = "transacoes", indexes = @Index(name = "idx_transacoes_usuario", columnList = "usuarioId, criadaEm"))
public class Transacao {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoTransacao tipo;

    /** Positivo = entrada, negativo = saida. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal valor;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal saldoApos;

    @Column(unique = true, length = 64)
    private String referencia;

    private String descricao;

    @Column(nullable = false)
    private Instant criadaEm;

    protected Transacao() {
        // JPA
    }

    public Transacao(String usuarioId, TipoTransacao tipo, BigDecimal valor, BigDecimal saldoApos,
                     String referencia, String descricao, Instant criadaEm) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.tipo = tipo;
        this.valor = valor;
        this.saldoApos = saldoApos;
        this.referencia = referencia;
        this.descricao = descricao;
        this.criadaEm = criadaEm;
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public TipoTransacao getTipo() {
        return tipo;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public BigDecimal getSaldoApos() {
        return saldoApos;
    }

    public String getReferencia() {
        return referencia;
    }

    public String getDescricao() {
        return descricao;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }
}
