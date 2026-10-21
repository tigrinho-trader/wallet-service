package com.tigrinhotrader.wallet.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.wallet.binance.BinanceSubcontaService;
import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.CarteiraRepository;
import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import com.tigrinhotrader.wallet.dominio.Transacao;
import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import com.tigrinhotrader.wallet.pagamento.GatewayPagamento;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Saldo inicial vindo da configuracao e credito de cupom, sem subir o Spring. */
class CarteiraServiceTest {

    private final CarteiraRepository carteiras = mock(CarteiraRepository.class);
    private final TransacaoRepository transacoes = mock(TransacaoRepository.class);
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void preparar() {
        when(carteiras.findById(any())).thenReturn(Optional.empty());
        when(carteiras.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CarteiraService servico(BigDecimal saldoInicial) {
        return new CarteiraService(carteiras, transacoes, mock(GatewayPagamento.class), relogio, saldoInicial,
                mock(BinanceSubcontaService.class), false);
    }

    @Test
    void saldoInicialZeroCriaCarteiraVaziaSemTransacao() {
        Carteira nova = servico(BigDecimal.ZERO).obterOuCriar("u0");

        assertThat(nova.getSaldo()).isEqualByComparingTo("0");
        verify(transacoes, never()).save(any());
    }

    @Test
    void saldoInicialVazioOuNegativoViraZero() {
        assertThat(servico(null).obterOuCriar("u1").getSaldo()).isEqualByComparingTo("0");
        assertThat(servico(new BigDecimal("-5")).obterOuCriar("u2").getSaldo()).isEqualByComparingTo("0");
        verify(transacoes, never()).save(any());
    }

    @Test
    void saldoInicialConfiguradoViraBonusDeBoasVindas() {
        Carteira nova = servico(new BigDecimal("250")).obterOuCriar("u3");

        assertThat(nova.getSaldo()).isEqualByComparingTo("250.00");
        ArgumentCaptor<Transacao> gravada = ArgumentCaptor.forClass(Transacao.class);
        verify(transacoes).save(gravada.capture());
        assertThat(gravada.getValue().getTipo()).isEqualTo(TipoTransacao.BONUS_INICIAL);
        assertThat(gravada.getValue().getValor()).isEqualByComparingTo("250");
        assertThat(gravada.getValue().getDescricao()).isEqualTo("Bonus de boas-vindas");
    }

    @Test
    void carteiraExistenteNaoGanhaBonusDeNovo() {
        Carteira antiga = new Carteira("u4", new BigDecimal("7"), relogio.instant());
        when(carteiras.findById("u4")).thenReturn(Optional.of(antiga));

        assertThat(servico(new BigDecimal("1000")).obterOuCriar("u4").getSaldo()).isEqualByComparingTo("7");
        verify(carteiras, never()).save(any());
        verify(transacoes, never()).save(any());
    }

    @Test
    void creditarCupomGravaTransacaoCupom() {
        Carteira carteira = servico(BigDecimal.ZERO).creditarCupom("u5", new BigDecimal("30.00"), "cupom:30COINS:u5",
                "Cupom 30COINS");

        assertThat(carteira.getSaldo()).isEqualByComparingTo("30.00");
        ArgumentCaptor<Transacao> gravada = ArgumentCaptor.forClass(Transacao.class);
        verify(transacoes).saveAndFlush(gravada.capture());
        assertThat(gravada.getValue().getTipo()).isEqualTo(TipoTransacao.CUPOM);
        assertThat(gravada.getValue().getReferencia()).isEqualTo("cupom:30COINS:u5");
        assertThat(gravada.getValue().getDescricao()).isEqualTo("Cupom 30COINS");
        assertThat(gravada.getValue().getSaldoApos()).isEqualByComparingTo("30.00");
    }

    @Test
    void creditarCupomJaUsadoNaoMexeNaCarteira() {
        when(transacoes.existsByReferencia("cupom:30COINS:u6")).thenReturn(true);

        assertThatThrownBy(() -> servico(BigDecimal.ZERO).creditarCupom("u6", new BigDecimal("30"),
                "cupom:30COINS:u6", "Cupom 30COINS"))
                .isInstanceOf(CupomJaUsadoException.class)
                .hasMessage("Cupom ja usado nesta conta");
        verify(carteiras, never()).save(any());
        verify(transacoes, never()).saveAndFlush(any());
    }

    @Test
    void saqueBinanceDebitaERegistraOBtcEnviado() {
        BinanceSubcontaService binance = mock(BinanceSubcontaService.class);
        when(binance.enviarBonus("u7", new BigDecimal("40"))).thenReturn(new BigDecimal("0.00001234"));
        CarteiraService servico = new CarteiraService(carteiras, transacoes, mock(GatewayPagamento.class), relogio,
                new BigDecimal("100"), binance, true);

        assertThat(servico.sacar("u7", new BigDecimal("40")).getSaldo()).isEqualByComparingTo("60.00");
        ArgumentCaptor<Transacao> gravadas = ArgumentCaptor.forClass(Transacao.class);
        verify(transacoes, org.mockito.Mockito.times(2)).save(gravadas.capture());
        Transacao saque = gravadas.getAllValues().get(1);
        assertThat(saque.getTipo()).isEqualTo(TipoTransacao.SAQUE);
        assertThat(saque.getValor()).isEqualByComparingTo("-40");
        assertThat(saque.getReferencia()).isEqualTo("binance-btc-0.00001234");
        assertThat(saque.getDescricao()).isEqualTo("Saque Binance: 0.00001234 BTC");
    }

    @Test
    void saqueBinanceRecusaValorInvalidoOuMaiorQueOSaldo() {
        BinanceSubcontaService binance = mock(BinanceSubcontaService.class);
        CarteiraService servico = new CarteiraService(carteiras, transacoes, mock(GatewayPagamento.class), relogio,
                new BigDecimal("100"), binance, true);

        assertThatThrownBy(() -> servico.sacar("u8", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servico.sacar("u8", BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servico.sacar("u8", new BigDecimal("100.01")))
                .isInstanceOf(SaldoInsuficienteException.class);
        verify(binance, never()).enviarBonus(any(), any());
    }
}
