package com.tigrinhotrader.wallet.repasse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.wallet.repasse.Repasse.Etapa;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepasseServiceTest {

    private static final String DERIV = "0xf17991177DA858B3Da4B86c8079ab3a06018deAE";
    private static final String PONTE = "0x57dEdD9cb2408193bA442deA32601a9096354CcF";

    private final List<Repasse> banco = new ArrayList<>();
    private final List<EntradaRepasse> fila = new ArrayList<>();
    private RepasseRepository repasses;
    private EntradaRepasseRepository entradas;
    private AsaasCaixa asaas;
    private BinanceCaixa binance;
    private CarteiraBsc carteira;
    private DerivCaixa deriv;
    private Instant agora = Instant.parse("2026-11-04T18:00:00Z");

    @BeforeEach
    void preparar() {
        repasses = mock(RepasseRepository.class);
        when(repasses.save(any(Repasse.class))).thenAnswer(i -> {
            Repasse r = i.getArgument(0);
            if (!banco.contains(r)) {
                banco.add(r);
            }
            return r;
        });
        when(repasses.findFirstByEtapaNotInOrderByCriadoEmAsc(any()))
                .thenAnswer(i -> banco.stream().filter(r -> !r.etapa.finalizada()).findFirst());
        when(repasses.findFirstByOrderByCriadoEmDesc())
                .thenAnswer(i -> banco.stream().max(Comparator.comparing(r -> r.criadoEm)));

        entradas = mock(EntradaRepasseRepository.class);
        when(entradas.save(any(EntradaRepasse.class))).thenAnswer(i -> guardar(i.getArgument(0)));
        when(entradas.saveAll(anyIterable())).thenAnswer(i -> {
            Iterable<EntradaRepasse> todas = i.getArgument(0);
            todas.forEach(this::guardar);
            return todas;
        });
        when(entradas.findByRepasseIdIsNullOrderByCriadaEmAsc())
                .thenAnswer(i -> new ArrayList<>(fila.stream().filter(e -> e.repasseId == null).toList()));
        when(entradas.findByRepasseId(any()))
                .thenAnswer(i -> new ArrayList<>(fila.stream().filter(e -> i.getArgument(0).equals(e.repasseId)).toList()));
        when(entradas.existsById(any())).thenAnswer(i -> fila.stream().anyMatch(e -> e.id.equals(i.getArgument(0))));

        asaas = mock(AsaasCaixa.class);
        binance = mock(BinanceCaixa.class);
        carteira = mock(CarteiraBsc.class);
        deriv = mock(DerivCaixa.class);
        when(asaas.configurado()).thenReturn(true);
        when(binance.configurado()).thenReturn(true);
        when(carteira.configurada()).thenReturn(true);
        when(carteira.endereco()).thenReturn(PONTE);
        when(deriv.configurada()).thenReturn(true);

        when(asaas.saldo()).thenReturn(new BigDecimal("57.21"));
        when(binance.redeBsc("USDT")).thenReturn(new BinanceCaixa.Rede(new BigDecimal("0.01"), new BigDecimal("5")));
        when(binance.preco("USDTBRL")).thenReturn(new BigDecimal("5.47"));
        when(carteira.gasSuficiente()).thenReturn(true);
    }

    private RepasseService servico(boolean habilitado, String derivEndereco) {
        RepasseProperties props = new RepasseProperties(habilitado, BigDecimal.ZERO, "chave", derivEndereco,
                "rpc", "deriv", "token", "app", Duration.ofHours(3), Duration.ofHours(6), Duration.ofMinutes(5));
        Clock relogio = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return agora; }
        };
        return new RepasseService(repasses, entradas, asaas, binance, carteira, deriv, props, relogio) {
            @Override
            void dormir(long ms) {
            }
        };
    }

    private EntradaRepasse guardar(EntradaRepasse e) {
        if (!fila.contains(e)) {
            fila.add(e);
        }
        return e;
    }

    /** Uma compra de moedas paga, na fila do repasse, com o valor liquido ja conhecido. */
    private EntradaRepasse compra(String liquido) {
        EntradaRepasse e = new EntradaRepasse(UUID.randomUUID(), "COB" + fila.size(), new BigDecimal(liquido), agora);
        e.liquido = new BigDecimal(liquido);
        return guardar(e);
    }

    private static String tlv(String id, String valor) {
        return id + String.format("%02d", valor.length()) + valor;
    }

    private static String codigoPix(String valor) {
        return tlv("00", "01") + tlv("26", tlv("00", "br.gov.bcb.pix") + tlv("25", "pix.exemplo/qr/abc"))
                + tlv("53", "986") + tlv("54", valor) + tlv("58", "BR") + tlv("59", "OSCAR R DE S FILHO")
                + tlv("60", "SAO PAULO") + "6304ABCD";
    }

    private static BigInteger wei(String usdt) {
        return new BigDecimal(usdt).movePointRight(18).toBigInteger();
    }

    private void binanceAbrePedidoComCodigo(String valorNoCodigo) {
        when(binance.pedirDepositoPix(new BigDecimal("57.21"))).thenReturn("ORD1");
        when(binance.codigoPix("ORD1")).thenReturn(codigoPix(valorNoCodigo));
    }

    @Test
    void levaODinheiroDoAsaasAteAContaOptions() {
        EntradaRepasse entrada = compra("57.21");
        binanceAbrePedidoComCodigo("57.21");
        when(asaas.saldo()).thenReturn(new BigDecimal("57.21"), BigDecimal.ZERO); // o Pix esvazia o Asaas
        when(binance.livre("BRL")).thenReturn(new BigDecimal("0.68"), new BigDecimal("57.89"));
        when(asaas.pagarPix(anyString(), eq(new BigDecimal("57.21")), anyString()))
                .thenReturn(new AsaasCaixa.Pix("PIX1", "AWAITING_REQUEST", null));
        when(asaas.consultarPix("PIX1")).thenReturn(new AsaasCaixa.Pix("PIX1", "DONE", null));
        when(binance.comprarUsdt(eq(new BigDecimal("57.21")), anyString())).thenReturn(new BigDecimal("10.40"));
        when(binance.sacarAutocustodia("USDT", new BigDecimal("10.40"), PONTE)).thenReturn("77004692");
        when(carteira.saldoUsdtWei()).thenReturn(BigInteger.ZERO, wei("10.39"));
        when(deriv.carteiraPrincipal()).thenReturn(new DerivCaixa.Carteira("W", BigDecimal.ZERO),
                new DerivCaixa.Carteira("W", new BigDecimal("10.39")));
        when(carteira.enviarUsdt(DERIV, wei("10.39"))).thenReturn("0xHASH");
        when(carteira.confirmada("0xHASH")).thenReturn(Optional.of(true));
        when(deriv.transferirParaOptions(any(UUID.class))).thenReturn(new BigDecimal("10.37"));

        RepasseService servico = servico(true, DERIV);
        List<Etapa> etapas = new ArrayList<>();
        for (int ciclo = 0; ciclo < 8; ciclo++) {
            servico.ciclo();
            etapas.add(banco.get(0).etapa);
        }

        assertThat(etapas).startsWith(Etapa.PIX_ENVIADO, Etapa.AGUARDANDO_BRL, Etapa.AGUARDANDO_CARTEIRA,
                Etapa.ENVIADO_DERIV, Etapa.AGUARDANDO_DERIV, Etapa.CONCLUIDO);
        assertThat(banco).hasSize(1); // concluido e Asaas zerado: nao inicia outro
        verify(asaas, times(1)).pagarPix(anyString(), any(), anyString());
        verify(carteira, times(1)).enviarUsdt(anyString(), any());
        verify(deriv, times(1)).transferirParaOptions(any(UUID.class));
        assertThat(banco.get(0).valorOptions).isEqualByComparingTo("10.37");
        assertThat(entrada.repasseId).isEqualTo(banco.get(0).id); // a compra saiu da fila
    }

    @Test
    void asaasRecusandoOPixFalhaSemTentarDeNovoLogoEmSeguida() {
        EntradaRepasse entrada = compra("57.21");
        binanceAbrePedidoComCodigo("57.21");
        when(binance.livre("BRL")).thenReturn(BigDecimal.ZERO);
        when(asaas.pagarPix(anyString(), any(), anyString())).thenThrow(new RepasseException("Asaas respondeu 400: saldo"));

        RepasseService servico = servico(true, DERIV);
        servico.ciclo();
        servico.ciclo();

        assertThat(banco).hasSize(1);
        assertThat(banco.get(0).etapa).isEqualTo(Etapa.FALHOU);
        assertThat(banco.get(0).erro).contains("continua no Asaas");
        verify(binance, times(1)).pedirDepositoPix(any());
        assertThat(entrada.repasseId).isNull(); // o dinheiro nao saiu: volta para a fila (nao vira caixa de saque)
    }

    @Test
    void servicoQueParouNoMeioDoPagamentoNuncaPagaDeNovo() {
        Repasse r = new Repasse(new BigDecimal("57.21"), agora);
        r.etapa = Etapa.PAGANDO;
        banco.add(r);
        EntradaRepasse entrada = compra("57.21");
        entrada.repasseId = r.id;

        servico(true, DERIV).ciclo();

        assertThat(r.etapa).isEqualTo(Etapa.FALHOU);
        assertThat(r.erro).contains("parou no meio");
        verify(asaas, never()).pagarPix(anyString(), any(), anyString());
        assertThat(entrada.repasseId).isEqualTo(r.id); // nao se sabe se pagou: nao volta para a fila
    }

    @Test
    void codigoPixComValorDiferenteNaoEPago() {
        EntradaRepasse entrada = compra("57.21");
        binanceAbrePedidoComCodigo("5721.00");
        when(binance.livre("BRL")).thenReturn(BigDecimal.ZERO);

        servico(true, DERIV).ciclo();

        assertThat(banco.get(0).etapa).isEqualTo(Etapa.FALHOU);
        verify(asaas, never()).pagarPix(anyString(), any(), anyString());
        assertThat(entrada.repasseId).isNull(); // nada foi pago: volta para a fila
    }

    @Test
    void compraAbaixoDoMinimoNaoIniciaRepasse() {
        compra("12.00");

        servico(true, DERIV).ciclo();

        assertThat(banco).isEmpty();
        verify(binance, never()).pedirDepositoPix(any());
    }

    @Test
    void repassePresoAlemDoPrazoViraFalha() {
        Repasse r = new Repasse(new BigDecimal("57.21"), agora.minus(Duration.ofHours(4)));
        r.etapa = Etapa.AGUARDANDO_BRL;
        banco.add(r);

        servico(true, DERIV).ciclo();

        assertThat(r.etapa).isEqualTo(Etapa.FALHOU);
        assertThat(r.erro).contains("prazo").contains("Binance");
    }

    @Test
    void semConfiguracaoCompletaNaoFazNada() {
        RepasseService servico = servico(true, "");
        servico.ciclo();
        assertThat(servico.ativo()).isFalse();

        RepasseService desligado = servico(false, DERIV);
        desligado.ciclo();
        assertThat(desligado.ativo()).isFalse();

        verify(asaas, never()).saldo();
        verify(binance, never()).pedirDepositoPix(any());
        verify(deriv, never()).carteiraPrincipal();
        verifyNoInteractions(repasses);
    }

    @Test
    void ociosoSoConfereAFilaDeTemposEmTemposOuQuandoChegaPix() {
        RepasseService servico = servico(true, DERIV);

        servico.ciclo();                 // primeira consulta
        servico.ciclo();                 // logo depois: nao consulta de novo
        verify(entradas, times(1)).findByRepasseIdIsNullOrderByCriadaEmAsc();
        verify(asaas, never()).saldo();  // fila vazia: nem pergunta o saldo

        when(asaas.valorLiquido("COB1")).thenReturn(new BigDecimal("9.01"));
        servico.pixRecebido(UUID.randomUUID(), "COB1", 1000); // Pix confirmado: consulta ja no proximo ciclo
        servico.ciclo();
        verify(entradas, times(2)).findByRepasseIdIsNullOrderByCriadaEmAsc();

        agora = agora.plus(Duration.ofMinutes(6)); // passou o intervalo ocioso
        servico.ciclo();
        verify(entradas, times(3)).findByRepasseIdIsNullOrderByCriadaEmAsc();
    }

    @Test
    void comprasAbaixoDoSaqueMinimoDaBinanceFicamJuntando() {
        compra("27.50"); // 27,50 / 5,47 = 5,03 USDT < 5 x 1,02

        servico(true, DERIV).ciclo();

        assertThat(banco).isEmpty();
        verify(binance, never()).pedirDepositoPix(any());
    }

    @Test
    void variavelDoRepasseVaziaUsaAChaveGeral() {
        assertThat(RepasseConfig.preenchido("", "geral")).isEqualTo("geral");
        assertThat(RepasseConfig.preenchido("  ", "geral")).isEqualTo("geral");
        assertThat(RepasseConfig.preenchido(null, "geral")).isEqualTo("geral");
        assertThat(RepasseConfig.preenchido("propria", "geral")).isEqualTo("propria");
    }

    @Test
    void caixaDeSaqueNaoVaiParaADeriv() {
        when(asaas.saldo()).thenReturn(new BigDecimal("357.21")); // R$ 300 de caixa + R$ 57,21 de compras
        compra("57.21");
        binanceAbrePedidoComCodigo("57.21");
        when(binance.livre("BRL")).thenReturn(BigDecimal.ZERO);
        when(asaas.pagarPix(anyString(), any(), anyString())).thenReturn(new AsaasCaixa.Pix("PIX1", "AWAITING_REQUEST", null));

        servico(true, DERIV).ciclo();

        assertThat(banco.get(0).valorReais).isEqualByComparingTo("57.21");
        verify(asaas).pagarPix(anyString(), eq(new BigDecimal("57.21")), anyString());
    }

    @Test
    void semCompraNaFilaNaoRepassaNemComSaldoNoAsaas() {
        when(asaas.saldo()).thenReturn(new BigDecimal("300.00")); // recarga feita direto no Asaas

        servico(true, DERIV).ciclo();

        assertThat(banco).isEmpty();
        verify(binance, never()).pedirDepositoPix(any());
    }

    @Test
    void valorLiquidoDaCompraVemDoAsaas() {
        EntradaRepasse e = guardar(new EntradaRepasse(UUID.randomUUID(), "COBX", new BigDecimal("60.00"), agora));
        when(asaas.valorLiquido("COBX")).thenReturn(new BigDecimal("59.01"));
        when(asaas.saldo()).thenReturn(new BigDecimal("400.00"));
        when(binance.pedirDepositoPix(new BigDecimal("59.01"))).thenReturn("ORD2");
        when(binance.codigoPix("ORD2")).thenReturn(codigoPix("59.01"));
        when(binance.livre("BRL")).thenReturn(BigDecimal.ZERO);
        when(asaas.pagarPix(anyString(), any(), anyString())).thenReturn(new AsaasCaixa.Pix("PIX2", "AWAITING_REQUEST", null));

        servico(true, DERIV).ciclo();

        assertThat(e.liquido).isEqualByComparingTo("59.01");
        assertThat(banco.get(0).valorReais).isEqualByComparingTo("59.01"); // a taxa do Asaas nao sai do caixa
    }

    @Test
    void pixRecebidoEntraNaFilaUmaVezESoComORepasseLigado() {
        UUID compraId = UUID.randomUUID();
        RepasseService servico = servico(true, DERIV);
        servico.pixRecebido(compraId, "COB1", 6000);
        servico.pixRecebido(compraId, "COB1", 6000); // a mesma compra de novo nao duplica

        assertThat(fila).hasSize(1);
        assertThat(fila.get(0).bruto).isEqualByComparingTo("60.00");

        servico(false, DERIV).pixRecebido(UUID.randomUUID(), "COB2", 5000); // desligado: o dinheiro fica no Asaas
        assertThat(fila).hasSize(1);
    }

    @Test
    void caixaLivreDescontaAFilaEORepasseQueAindaEstaNoAsaas() {
        when(asaas.saldo()).thenReturn(new BigDecimal("400.00"));
        compra("50.00");
        guardar(new EntradaRepasse(UUID.randomUUID(), "COBY", new BigDecimal("20.00"), agora)); // liquido ainda nao
        EntradaRepasse jaLevada = compra("99.00");
        jaLevada.repasseId = UUID.randomUUID();
        Repasse emAndamento = new Repasse(new BigDecimal("30.00"), agora);
        banco.add(emAndamento);

        RepasseService servico = servico(true, DERIV);
        assertThat(servico.caixaLivre()).isEqualByComparingTo("300.00"); // 400 - 50 - 20 - 30

        emAndamento.etapa = Etapa.AGUARDANDO_BRL; // o Pix ja saiu do Asaas
        assertThat(servico.caixaLivre()).isEqualByComparingTo("330.00");

        when(asaas.saldo()).thenReturn(new BigDecimal("10.00"));
        assertThat(servico.caixaLivre()).isEqualByComparingTo("0.00"); // nunca negativo
    }
}
