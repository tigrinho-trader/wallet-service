package com.tigrinhotrader.wallet.painel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.CarteiraRepository;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa;
import com.tigrinhotrader.wallet.repasse.BinanceCaixa;
import com.tigrinhotrader.wallet.repasse.CarteiraBsc;
import com.tigrinhotrader.wallet.repasse.DerivCaixa;
import com.tigrinhotrader.wallet.repasse.RepasseException;
import com.tigrinhotrader.wallet.repasse.RepasseService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Painel financeiro: saldos e fluxo de cada lugar, total em reais e o que acontece quando um lugar nao responde. */
class FinanceiroServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Instant agora = Instant.parse("2026-11-16T15:00:00Z");
    private final AsaasCaixa asaas = mock(AsaasCaixa.class);
    private final BinanceCaixa binance = mock(BinanceCaixa.class);
    private final CarteiraBsc carteira = mock(CarteiraBsc.class);
    private final DerivCaixa deriv = mock(DerivCaixa.class);
    private final RepasseService repasse = mock(RepasseService.class);
    private final CarteiraRepository carteiras = mock(CarteiraRepository.class);
    private FinanceiroService servico;

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }

    @BeforeEach
    void preparar() throws Exception {
        when(asaas.configurado()).thenReturn(true);
        when(binance.configurado()).thenReturn(true);
        when(carteira.configurada()).thenReturn(true);
        when(deriv.configurada()).thenReturn(true);

        when(asaas.saldo()).thenReturn(new BigDecimal("300.00"));
        when(asaas.extrato(any(), anyInt())).thenReturn(json("""
                [{"date":"2026-11-16","type":"PAYMENT_RECEIVED","value":40.00,"description":"Compra de moedas"},
                 {"date":"2026-11-16","type":"PAYMENT_FEE","value":-0.79},
                 {"date":"2026-11-16","type":"PIX_TRANSACTION_DEBIT","value":-39.21,"description":"Repasse Binance"}]
                """));
        when(repasse.caixaLivreDe(new BigDecimal("300.00"))).thenReturn(new BigDecimal("260.79"));

        when(binance.preco("USDTBRL")).thenReturn(new BigDecimal("5.50"));
        when(binance.saldos()).thenReturn(Map.of("BRL", new BigDecimal("10"), "USDT", new BigDecimal("2"),
                "BNB", new BigDecimal("0.01")));
        when(binance.ordensFiat(0, Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())).thenReturn(json("""
                [{"amount":"39.21","status":"Successful","createTime":1794830000000}]"""));
        when(binance.ordensFiat(1, Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())).thenReturn(json("[]"));
        when(binance.negocios("USDTBRL")).thenReturn(json("""
                [{"qty":"7.10","quoteQty":"39.20","isBuyer":true,"time":1794830100000},
                 {"qty":"1.00","quoteQty":"5.40","isBuyer":true,"time":1700000000000}]"""));
        when(binance.saquesCripto(anyLong())).thenReturn(json("""
                [{"coin":"USDT","network":"BSC","amount":"7.00","status":6,"applyTime":"2026-11-16 12:00:00"}]"""));

        when(carteira.endereco()).thenReturn("0xPONTE");
        when(carteira.saldoUsdtWei()).thenReturn(BigInteger.ZERO);
        when(carteira.saldoBnbWei()).thenReturn(new BigInteger("1000000000000000"));
        when(carteira.gasSuficiente()).thenReturn(true);

        when(deriv.carteiraPrincipal()).thenReturn(new DerivCaixa.Carteira("W", Map.of("USDT", new BigDecimal("2"))));
        when(deriv.saldoOptions()).thenReturn(new DerivCaixa.SaldoOptions("ROT1", new BigDecimal("14.15"), "USD"));

        when(carteiras.findAll()).thenReturn(List.of(new Carteira("auth0|ana", new BigDecimal("100"), agora),
                new Carteira(Poupanca.CARTEIRA, new BigDecimal("5"), agora)));
        when(repasse.situacao()).thenReturn(new RepasseService.Situacao(true, 1, new BigDecimal("30.00"), null, null,
                "Juntando", List.of()));

        servico = new FinanceiroService(asaas, binance, carteira, deriv, repasse, carteiras,
                Clock.fixed(agora, ZoneOffset.UTC));
    }

    @Test
    void juntaOsSaldosDeCadaLugarEOFluxoDoPeriodo() {
        FinanceiroService.Financeiro f = servico.financeiro(7);

        FinanceiroService.Asaas a = f.asaas().dados();
        assertThat(a.saldo()).isEqualByComparingTo("300");
        assertThat(a.caixaLivre()).isEqualByComparingTo("260.79");
        assertThat(a.entradas()).isEqualByComparingTo("40");
        assertThat(a.taxas()).isEqualByComparingTo("0.79");
        assertThat(a.saidas()).isEqualByComparingTo("39.21");
        assertThat(a.movimentos()).extracting(FinanceiroService.Movimento::situacao)
                .contains("Pix recebido", "Taxa do Asaas", "Pix enviado");

        FinanceiroService.Binance b = f.binance().dados();
        assertThat(b.brl()).isEqualByComparingTo("10");
        assertThat(b.depositosBrl()).isEqualByComparingTo("39.21");
        assertThat(b.usdtComprado()).isEqualByComparingTo("7.10");        // a compra de antes do periodo nao conta
        assertThat(b.reaisEmUsdt()).isEqualByComparingTo("39.20");
        assertThat(b.usdtSacado()).isEqualByComparingTo("7");
        assertThat(b.movimentos()).anyMatch(m -> m.quando().equals("2026-11-16T09:00"));   // 12:00 UTC

        assertThat(f.ponte().dados().bnb()).isEqualByComparingTo("0.001");
        assertThat(f.deriv().dados().options()).isEqualByComparingTo("14.15");

        // reais: 300 (Asaas) + 10 (Binance); USDT: 2 (Binance) + 0 (ponte) + 2 (Deriv) + 14,15 (Options) = 18,15 x 5,50
        assertThat(f.totalReais()).isEqualByComparingTo("409.83");
        assertThat(f.jogo().moedasPerfis()).isEqualByComparingTo("100");
        assertThat(f.jogo().perfis()).isEqualTo(1);
        assertThat(f.jogo().poupanca()).isEqualByComparingTo("5");
        assertThat(f.sobra()).isEqualByComparingTo("309.83");
        assertThat(f.jogo().naFilaRepasse()).isEqualTo(1);
    }

    @Test
    void lugarQueNaoRespondeVemComOErroEOsOutrosAparecem() {
        when(binance.saldos()).thenThrow(new RepasseException("Binance respondeu 401: IP nao autorizado"));

        FinanceiroService.Financeiro f = servico.financeiro(7);

        assertThat(f.binance().dados()).isNull();
        assertThat(f.binance().erro()).contains("IP nao autorizado");
        assertThat(f.asaas().dados()).isNotNull();
        assertThat(f.deriv().dados()).isNotNull();
        // sem a Binance: 300 + (0 + 2 + 14,15) x 5,50
        assertThat(f.totalReais()).isEqualByComparingTo("388.83");
    }

    @Test
    void guardaOResultadoPor20sParaNaoMartelarAsCorretoras() {
        FinanceiroService.Financeiro primeiro = servico.financeiro(7);
        assertThat(servico.financeiro(7)).isSameAs(primeiro);
        verify(binance, times(1)).saldos();
        servico.financeiro(30);                                             // outro periodo consulta de novo
        verify(binance, times(2)).saldos();
    }

    @Test
    void semConfiguracaoCadaParteDizIsso() {
        when(asaas.configurado()).thenReturn(false);
        when(binance.configurado()).thenReturn(false);
        FinanceiroService.Financeiro f = servico.financeiro(1);
        assertThat(f.asaas().erro()).isEqualTo("não configurado neste ambiente");
        assertThat(f.binance().erro()).isEqualTo("não configurado neste ambiente");
        assertThat(f.cotacaoUsdt()).isNull();
    }
}
