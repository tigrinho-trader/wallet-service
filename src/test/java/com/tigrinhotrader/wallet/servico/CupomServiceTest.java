package com.tigrinhotrader.wallet.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class CupomServiceTest {

    private final CarteiraService carteiras = mock(CarteiraService.class);
    private final TransacaoRepository transacoes = mock(TransacaoRepository.class);

    private CupomService cupom(String codigo, String moedas) {
        return new CupomService(carteiras, transacoes, codigo, moedas == null ? null : new BigDecimal(moedas));
    }

    private void saldoDepois(String saldo) {
        when(carteiras.creditarCupom(anyString(), any(), anyString(), anyString()))
                .thenReturn(new Carteira("u", new BigDecimal(saldo), Instant.EPOCH));
    }

    @Test
    void codigoCertoCreditaUmaVezComReferenciaUnicaPorConta() {
        saldoDepois("1030");

        CupomService.Resgate resgate = cupom("30COINS", "30").resgatar("u1", "30COINS");

        assertThat(resgate.moedas()).isEqualByComparingTo("30.00");
        assertThat(resgate.moedas().scale()).isEqualTo(2);
        assertThat(resgate.saldo()).isEqualByComparingTo("1030.00");
        verify(carteiras).creditarCupom("u1", new BigDecimal("30.00"), "cupom:30COINS:u1", "Cupom 30COINS");
    }

    @Test
    void comparaSemEspacosESemDiferenciarMaiusculas() {
        saldoDepois("30");

        cupom(" 30coins ", "30").resgatar("u2", "  30Coins\t");

        verify(carteiras).creditarCupom("u2", new BigDecimal("30.00"), "cupom:30COINS:u2", "Cupom 30COINS");
    }

    @Test
    void codigoErradoOuVazioEInvalido() {
        CupomService servico = cupom("30COINS", "30");
        for (String errado : new String[] {"31COINS", "", "   ", null}) {
            assertThatThrownBy(() -> servico.resgatar("u3", errado))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessage("Cupom invalido");
        }
        verify(carteiras, never()).creditarCupom(anyString(), any(), anyString(), anyString());
    }

    @Test
    void semCupomConfiguradoNenhumCodigoVale() {
        // codigo vazio, moedas zero/negativas ou sem valor
        CupomService[] desligados = {cupom("", "30"), cupom(null, "30"), cupom("30COINS", "0"),
            cupom("30COINS", "-1"), cupom("30COINS", null)};
        for (CupomService servico : desligados) {
            assertThat(servico.habilitado()).isFalse();
            assertThatThrownBy(() -> servico.resgatar("u4", "30COINS"))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessage("Cupom invalido");
            assertThatThrownBy(() -> servico.resgatar("u4", ""))
                    .isInstanceOf(RegraNegocioException.class);
        }
        verify(carteiras, never()).creditarCupom(anyString(), any(), anyString(), anyString());
    }

    @Test
    void jaUsadoSobeComo409() {
        when(carteiras.creditarCupom(anyString(), any(), anyString(), anyString()))
                .thenThrow(new CupomJaUsadoException());

        assertThatThrownBy(() -> cupom("30COINS", "30").resgatar("u5", "30coins"))
                .isInstanceOf(CupomJaUsadoException.class);
    }

    @Test
    void corridaComViolacaoDeUnicidadeViraJaUsado() {
        when(carteiras.creditarCupom(anyString(), any(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("duplicate key referencia"));
        when(transacoes.existsByReferencia("cupom:30COINS:u6")).thenReturn(true);

        assertThatThrownBy(() -> cupom("30COINS", "30").resgatar("u6", "30COINS"))
                .isInstanceOf(CupomJaUsadoException.class)
                .hasMessage("Cupom ja usado nesta conta");
    }

    @Test
    void corridaNaVersaoDaCarteiraTambemViraJaUsado() {
        when(carteiras.creditarCupom(anyString(), any(), anyString(), anyString()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Carteira.class, "u7"));
        when(transacoes.existsByReferencia("cupom:30COINS:u7")).thenReturn(true);

        assertThatThrownBy(() -> cupom("30COINS", "30").resgatar("u7", "30COINS"))
                .isInstanceOf(CupomJaUsadoException.class);
    }

    @Test
    void falhaDeBancoSemOCupomGravadoNaoEMascarada() {
        DataIntegrityViolationException erro = new DataIntegrityViolationException("outra restricao");
        when(carteiras.creditarCupom(anyString(), any(), anyString(), anyString())).thenThrow(erro);
        when(transacoes.existsByReferencia(anyString())).thenReturn(false);

        assertThatThrownBy(() -> cupom("30COINS", "30").resgatar("u8", "30COINS")).isSameAs(erro);
    }

    @Test
    void referenciaLongaViraHashQueCabeNaColuna() {
        String idLongo = "google-oauth2|" + "9".repeat(60);
        String referencia = CupomService.referencia("30COINS", idLongo);

        assertThat(referencia).startsWith("cupom:").hasSize(CupomService.TAMANHO_REFERENCIA);
        // estavel: mesmo par, mesma referencia; outra conta, outra referencia
        assertThat(CupomService.referencia("30COINS", idLongo)).isEqualTo(referencia);
        assertThat(CupomService.referencia("30COINS", idLongo + "1")).isNotEqualTo(referencia);
        assertThat(CupomService.referencia("30COINS", "auth0|abc")).isEqualTo("cupom:30COINS:auth0|abc");

        saldoDepois("30");
        cupom("30COINS", "30").resgatar(idLongo, "30COINS");
        verify(carteiras).creditarCupom(eq(idLongo), any(), eq(referencia), eq("Cupom 30COINS"));
    }
}
