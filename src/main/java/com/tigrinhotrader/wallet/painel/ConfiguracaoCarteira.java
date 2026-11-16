package com.tigrinhotrader.wallet.painel;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** O que o super-admin configurou no painel para a carteira (uma linha so, id 1). Campo nulo = vale o padrao. */
@Entity
@Table(name = "configuracao_carteira")
public class ConfiguracaoCarteira {

    static final int ID = 1;

    @Id
    private Integer id = ID;

    /** Menor compra de moedas por Pix (1 moeda = R$ 1). */
    Integer pixMinimoMoedas;
    Instant atualizadaEm;

    protected ConfiguracaoCarteira() {
        // JPA
    }

    static ConfiguracaoCarteira nova() {
        return new ConfiguracaoCarteira();
    }
}
