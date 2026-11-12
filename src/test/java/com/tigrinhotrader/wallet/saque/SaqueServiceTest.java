package com.tigrinhotrader.wallet.saque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa.Transferencia;
import com.tigrinhotrader.wallet.repasse.RepasseException;
import com.tigrinhotrader.wallet.repasse.RepasseService;
import com.tigrinhotrader.wallet.saque.SaquePix.Status;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;

class SaqueServiceTest {

    private static final String DONO = "auth0|dono";
    private static final String CPF = "529.982.247-25";

    private final List<SaquePix> banco = new ArrayList<>();
    private final List<Status> statusAoGravar = new ArrayList<>();
    private final Set<String> referencias = new HashSet<>();
    private SaquePixRepository saques;
    private CarteiraService carteiras;
    private AsaasCaixa asaas;
    private RepasseService repasse;
    private BigDecimal moedas = new BigDecimal("1000.00");   // saldo da carteira na plataforma
    private BigDecimal livre = new BigDecimal("300.00");     // caixa livre no Asaas
    private Instant agora = Instant.parse("2026-11-11T15:00:00Z"); // 12h em Brasilia

    @BeforeEach
    void preparar() {
        saques = mock(SaquePixRepository.class);
        when(saques.save(any(SaquePix.class))).thenAnswer(i -> {
            SaquePix s = i.getArgument(0);
            statusAoGravar.add(s.status);
            if (!banco.contains(s)) {
                banco.add(s);
            }
            return s;
        });
        when(saques.findTop20ByUsuarioIdOrderByCriadoEmDesc(anyString())).thenAnswer(i -> banco.stream()
                .filter(s -> s.usuarioId.equals(i.getArgument(0)))
                .sorted(Comparator.comparing((SaquePix s) -> s.criadoEm).reversed()).toList());

        // carteira de verdade em miniatura: debita so com saldo, referencia unica, estorno uma vez so
        carteiras = mock(CarteiraService.class);
        when(carteiras.obterOuCriar(anyString())).thenAnswer(i -> new Carteira(i.getArgument(0), moedas, agora));
        when(carteiras.debitarSaquePix(anyString(), any(), anyString(), anyString())).thenAnswer(i -> {
            BigDecimal quanto = i.getArgument(1);
            if (!referencias.add(i.getArgument(2))) {
                throw new IllegalStateException("debito repetido");
            }
            if (moedas.compareTo(quanto) < 0) {
                throw new SaldoInsuficienteException(moedas);
            }
            moedas = moedas.subtract(quanto);
            return null;
        });
        when(carteiras.estornarSaquePix(anyString(), any(), anyString(), anyString())).thenAnswer(i -> {
            if (!referencias.add(i.getArgument(2))) {
                return false;
            }
            moedas = moedas.add(i.getArgument(1));
            return true;
        });

        asaas = mock(AsaasCaixa.class);
        when(asaas.configurado()).thenReturn(true);
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new Transferencia("T1", "PENDING", true, BigDecimal.ZERO, "OSCAR R DE S FILHO", null));

        repasse = mock(RepasseService.class);
        when(repasse.caixaLivre()).thenAnswer(i -> livre);
        when(repasse.comCaixaLivre(any())).thenAnswer(i -> {
            Function<BigDecimal, Object> acao = i.getArgument(0);
            return acao.apply(livre);
        });
    }

    private SaqueService servico() {
        Clock relogio = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return agora; }
        };
        return new SaqueService(saques, carteiras, asaas, repasse, relogio, mock(PlatformTransactionManager.class), 100);
    }

    // ---------------------------------------------------------------- moedas da plataforma

    @Test
    void naoSacaMaisDoQueAsMoedasMesmoComDinheiroNoAsaas() {
        moedas = new BigDecimal("15.50");   // o Asaas tem R$ 300 livres, mas a plataforma so 15,5 moedas

        assertThatThrownBy(() -> servico().sacar(DONO, "CPF", CPF, new BigDecimal("20.00")))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("Saldo insuficiente na plataforma").hasMessageContaining("15.50 moedas");

        assertThat(moedas).isEqualByComparingTo("15.50");   // nada debitado
        assertThat(banco).isEmpty();                          // nenhum saque gravado
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void umCentavoAMaisQueAsMoedasTambemERecusado() {
        moedas = new BigDecimal("15.50");

        assertThatThrownBy(() -> servico().sacar(DONO, "CPF", CPF, new BigDecimal("15.51")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Saldo insuficiente na plataforma");
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void sacaTodasAsMoedasEDebitaAntesDePedirOPix() {
        moedas = new BigDecimal("15.50");

        SaquePix s = servico().sacar(DONO, "CPF", CPF, new BigDecimal("15.50"));

        assertThat(s.status).isEqualTo(Status.ENVIADO);
        assertThat(moedas).isEqualByComparingTo("0.00");
        InOrder ordem = inOrder(carteiras, saques, asaas);
        ordem.verify(carteiras).debitarSaquePix(eq(DONO), eq(new BigDecimal("15.50")), eq("saque-pix:" + s.id), anyString());
        ordem.verify(saques).save(any(SaquePix.class));
        ordem.verify(asaas).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void doisSaquesSeguidosNaoPassamDasMoedas() {
        moedas = new BigDecimal("30.00");
        SaqueService servico = servico();
        servico.sacar(DONO, "CPF", CPF, new BigDecimal("20.00"));

        assertThatThrownBy(() -> servico.sacar(DONO, "EMAIL", "a@b.com", new BigDecimal("20.00")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("10.00 moedas");
        verify(asaas, times(1)).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void moedasGastasNoMeioDoCaminhoBarramOSaque() {
        // a conferencia ve 50 moedas, mas uma rodada gasta tudo antes do debito: o debito recusa e nada sai
        doThrow(new SaldoInsuficienteException(BigDecimal.ZERO))
                .when(carteiras).debitarSaquePix(anyString(), any(), anyString(), anyString());
        moedas = new BigDecimal("50.00");

        assertThatThrownBy(() -> servico().sacar(DONO, "CPF", CPF, new BigDecimal("40.00")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Saldo insuficiente na plataforma");
        assertThat(banco).isEmpty();
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void pixRecusadoDevolveAsMoedasUmaVezSo() {
        moedas = new BigDecimal("100.00");
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new RepasseException("Asaas respondeu 400: Saldo insuficiente"));

        SaquePix s = servico().sacar(DONO, "CPF", CPF, new BigDecimal("40.00"));

        assertThat(s.status).isEqualTo(Status.FALHOU);
        assertThat(s.motivo).contains("recusou").contains("Saldo insuficiente");
        assertThat(moedas).isEqualByComparingTo("100.00");   // debitou 40 e devolveu 40
        assertThat(referencias).contains("estorno-saque-pix:" + s.id);
    }

    @Test
    void semRespostaDoAsaasNaoDevolveAsMoedas() {
        moedas = new BigDecimal("100.00");
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"));
        SaquePix s = servico().sacar(DONO, "CPF", CPF, new BigDecimal("90.00"));

        assertThat(s.status).isEqualTo(Status.CONFERIR);
        assertThat(s.motivo).contains("confira");
        assertThat(moedas).isEqualByComparingTo("10.00");    // pode ter saido: as moedas continuam debitadas
    }

    @Test
    void falhaDescobertaDepoisDevolveAsMoedasUmaVezSo() {
        moedas = new BigDecimal("100.00");
        SaqueService servico = servico();
        SaquePix s = servico.sacar(DONO, "CPF", CPF, new BigDecimal("40.00"));
        assertThat(moedas).isEqualByComparingTo("60.00");
        when(asaas.consultarTransferencia("T1"))
                .thenReturn(new Transferencia("T1", "FAILED", true, null, null, "Chave nao encontrada"));

        servico.resumo(DONO);
        servico.resumo(DONO);

        assertThat(s.status).isEqualTo(Status.FALHOU);
        assertThat(moedas).isEqualByComparingTo("100.00");
        verify(carteiras, times(1)).estornarSaquePix(anyString(), any(), anyString(), anyString());
    }

    @Test
    void disponivelEOMenorEntreMoedasECaixa() {
        moedas = new BigDecimal("15.50");
        assertThat(servico().resumo(DONO).disponivel()).isEqualByComparingTo("15.50");

        moedas = new BigDecimal("900.00");
        assertThat(servico().resumo(DONO).disponivel()).isEqualByComparingTo("300.00");
    }

    // ---------------------------------------------------------------- caixa do Asaas, limite e permissao

    @Test
    void qualquerContaLogadaPodeSacar() {
        SaqueService servico = servico();

        assertThat(servico.permitido(DONO)).isTrue();
        assertThat(servico.permitido("google|outra-conta")).isTrue();
        assertThat(servico.permitido(" ")).isFalse();
        assertThat(servico.permitido(null)).isFalse();
    }

    @Test
    void semAsaasDeProducaoNinguemSaca() {
        when(asaas.configurado()).thenReturn(false);
        assertThat(servico().permitido(DONO)).isFalse();
    }

    @Test
    void sacaPeloAsaasComAChaveNormalizadaEGravaAntesDePedir() {
        SaquePix s = servico().sacar(DONO, "cpf", CPF, new BigDecimal("100"));

        verify(asaas).transferirPix(eq("52998224725"), eq("CPF"), eq(new BigDecimal("100.00")),
                eq(SaqueService.DESCRICAO), eq(s.id.toString()));
        assertThat(statusAoGravar).containsExactly(Status.ENVIANDO, Status.ENVIADO); // gravado antes de chamar o Asaas
        assertThat(s.asaasId).isEqualTo("T1");
        assertThat(s.destinatario).isEqualTo("OSCAR R DE S FILHO");
    }

    @Test
    void naoSacaMaisQueOCaixaLivreDoAsaas() {
        livre = new BigDecimal("50.00");

        assertThatThrownBy(() -> servico().sacar(DONO, "CPF", CPF, new BigDecimal("60.00")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("caixa do Asaas").hasMessageContaining("50.00");
        assertThat(banco).isEmpty();
        assertThat(moedas).isEqualByComparingTo("1000.00");
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void cliqueRepetidoNaoPagaDuasVezes() {
        SaqueService servico = servico();
        servico.sacar(DONO, "CPF", CPF, BigDecimal.TEN);

        assertThatThrownBy(() -> servico.sacar(DONO, "CPF", CPF, BigDecimal.TEN))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("acabou de ser feito");

        agora = agora.plus(Duration.ofMinutes(3));
        assertThat(servico.sacar(DONO, "CPF", CPF, BigDecimal.TEN).status).isEqualTo(Status.ENVIADO);
    }

    @Test
    void pedidoDeTokenSmsApareceComoAguardandoAutorizacao() {
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new Transferencia("T2", "PENDING", false, null, null, null));

        assertThat(servico().sacar(DONO, "CPF", CPF, BigDecimal.TEN).status).isEqualTo(Status.AGUARDANDO_AUTORIZACAO);
    }

    @Test
    void resumoAtualizaOsSaquesEmAberto() {
        SaqueService servico = servico();
        SaquePix s = servico.sacar(DONO, "CPF", CPF, new BigDecimal("40.00"));
        when(asaas.consultarTransferencia("T1"))
                .thenReturn(new Transferencia("T1", "DONE", true, new BigDecimal("0.00"), "OSCAR R DE S FILHO", null));

        SaqueService.Resumo r = servico.resumo(DONO);

        assertThat(r.permitido()).isTrue();
        assertThat(s.status).isEqualTo(Status.CONCLUIDO);
        assertThat(r.moedas()).isEqualByComparingTo("960.00");
        assertThat(r.caixa()).isEqualByComparingTo("300.00");
        assertThat(r.recentes()).extracting(x -> x.id).containsExactly(s.id);
    }

    @Test
    void valorComMaisDeDuasCasasOuAbaixoDoMinimoERecusado() {
        SaqueService servico = servico();
        assertThatThrownBy(() -> servico.sacar(DONO, "CPF", CPF, new BigDecimal("10.005")))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> servico.sacar(DONO, "CPF", CPF, new BigDecimal("0.50")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("minimo");
        assertThat(banco).isEmpty();
    }

    @Test
    void moedasEReaisSeguemOPrecoDaMoeda() {
        SaqueService umReal = servico();
        assertThat(umReal.moedasDe(new BigDecimal("15.50"))).isEqualByComparingTo("15.50");

        SaqueService doisReais = new SaqueService(saques, carteiras, asaas, repasse, java.time.Clock.systemUTC(),
                mock(PlatformTransactionManager.class), 200); // 1 moeda = R$ 2
        assertThat(doisReais.moedasDe(new BigDecimal("15.01"))).isEqualByComparingTo("7.51"); // arredonda para cima
        assertThat(doisReais.reaisDe(new BigDecimal("7.50"))).isEqualByComparingTo("15.00");
    }
}
