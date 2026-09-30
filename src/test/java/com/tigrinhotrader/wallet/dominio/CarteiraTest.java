package com.tigrinhotrader.wallet.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CarteiraTest {

    private final Instant t0 = Instant.parse("2026-09-30T12:00:00Z");
    private final Instant t1 = t0.plusSeconds(10);

    private Carteira carteira(String saldo) {
        return new Carteira("u1", new BigDecimal(saldo), t0);
    }

    @Test
    void creditaEDebita() {
        Carteira c = carteira("100");
        c.creditar(new BigDecimal("50.5"), t1);
        c.debitar(new BigDecimal("20"), t1);
        assertThat(c.getSaldo()).isEqualByComparingTo("130.50");
        assertThat(c.getAtualizadaEm()).isEqualTo(t1);
        assertThat(c.getCriadaEm()).isEqualTo(t0);
        assertThat(c.getUsuarioId()).isEqualTo("u1");
    }

    @Test
    void naoDebitaMaisQueOSaldo() {
        Carteira c = carteira("10");
        assertThatThrownBy(() -> c.debitar(new BigDecimal("10.01"), t1))
                .isInstanceOf(SaldoInsuficienteException.class)
                .hasMessageContaining("10.00");
        assertThat(c.getSaldo()).isEqualByComparingTo("10");
    }

    @Test
    void recusaValoresNaoPositivos() {
        Carteira c = carteira("10");
        assertThatThrownBy(() -> c.creditar(BigDecimal.ZERO, t1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.debitar(new BigDecimal("-1"), t1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.creditar(null, t1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aplicaGanhoPerdaEEmpate() {
        Carteira c = carteira("100");
        assertThat(c.aplicarResultado(new BigDecimal("90"), t1)).isEqualByComparingTo("90");
        assertThat(c.aplicarResultado(new BigDecimal("-40"), t1)).isEqualByComparingTo("-40");
        assertThat(c.aplicarResultado(BigDecimal.ZERO, t1)).isEqualByComparingTo("0");
        assertThat(c.getSaldo()).isEqualByComparingTo("150");
    }

    @Test
    void prejuizoMaiorQueSaldoZeraSemNegativar() {
        Carteira c = carteira("30");
        assertThat(c.aplicarResultado(new BigDecimal("-100"), t1)).isEqualByComparingTo("-30");
        assertThat(c.getSaldo()).isEqualByComparingTo("0");
    }
}
