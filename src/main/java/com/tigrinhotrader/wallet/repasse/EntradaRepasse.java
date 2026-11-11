package com.tigrinhotrader.wallet.repasse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um Pix de compra de moedas que entrou no Asaas e precisa ir para a Deriv. O repasse leva so a soma das
 * entradas ainda nao repassadas, nunca o saldo inteiro: o resto do Asaas e o caixa de saque do dono.
 */
@Entity
@Table(name = "entradas_repasse", indexes = @Index(name = "idx_entradas_repasse", columnList = "repasseId, criadaEm"))
public class EntradaRepasse {

    /** O id da compra Pix: uma entrada por compra. */
    @Id
    UUID id;

    @Column(nullable = false, length = 64)
    String cobrancaId;

    /** Valor pago pelo jogador. */
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal bruto;

    /** Valor que caiu no Asaas, ja sem a taxa (netValue). Null = ainda nao consultado. */
    @Column(precision = 19, scale = 2)
    BigDecimal liquido;

    /** Repasse que levou esta entrada. Null = ainda esta na fila. */
    UUID repasseId;

    @Column(nullable = false)
    Instant criadaEm;

    protected EntradaRepasse() {
    }

    EntradaRepasse(UUID id, String cobrancaId, BigDecimal bruto, Instant agora) {
        this.id = id;
        this.cobrancaId = cobrancaId;
        this.bruto = bruto;
        this.criadaEm = agora;
    }

    /** O que esta entrada separa do saldo: o liquido, ou o bruto (maior) enquanto o liquido nao e conhecido. */
    BigDecimal reservado() {
        return liquido != null ? liquido : bruto;
    }
}
