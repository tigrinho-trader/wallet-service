package com.tigrinhotrader.wallet.saque;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um saque por Pix do caixa do Asaas para uma chave do dono. Nasce ENVIANDO (gravado antes de chamar o Asaas):
 * se o servico parar no meio, fica marcado para conferir em vez de ser repetido.
 */
@Entity
@Table(name = "saques_pix", indexes = @Index(name = "idx_saques_pix_usuario", columnList = "usuarioId, criadoEm"))
public class SaquePix {

    public enum Status {
        ENVIANDO,                // chamando o Asaas
        ENVIADO,                 // o Asaas aceitou; o Pix esta sendo processado
        AGUARDANDO_AUTORIZACAO,  // o Asaas pede o token SMS no app
        CONCLUIDO,
        FALHOU,                  // certo que nao saiu dinheiro
        CONFERIR;                // sem resposta do Asaas: pode ter saido ou nao

        public boolean emAberto() {
            return this == ENVIADO || this == AGUARDANDO_AUTORIZACAO;
        }
    }

    @Id
    UUID id;

    @Version
    Long versao;

    @Column(nullable = false)
    String usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    ChavePix.Tipo tipoChave;

    @Column(nullable = false, length = 80)
    String chave;

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    Status status;

    @Column(length = 64)
    String asaasId;

    @Column(length = 30)
    String statusAsaas;

    @Column(precision = 19, scale = 2)
    BigDecimal taxa;

    @Column(length = 120)
    String destinatario;

    @Column(length = 300)
    String motivo;

    @Column(nullable = false)
    Instant criadoEm;

    @Column(nullable = false)
    Instant atualizadoEm;

    protected SaquePix() {
    }

    SaquePix(UUID id, String usuarioId, ChavePix destino, BigDecimal valor, Instant agora) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.tipoChave = destino.tipo();
        this.chave = destino.valor();
        this.valor = valor;
        this.status = Status.ENVIANDO;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    void mudar(Status novo, String motivo, Instant agora) {
        this.status = novo;
        if (motivo != null) {
            this.motivo = motivo.length() > 300 ? motivo.substring(0, 300) : motivo;
        }
        this.atualizadoEm = agora;
    }

    String chaveMascarada() {
        return ChavePix.mascarar(tipoChave, chave);
    }
}
