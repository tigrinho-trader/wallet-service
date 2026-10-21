package com.tigrinhotrader.wallet.binance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Subconta Binance nos dois modos (LINK e STANDARD), com os clientes HTTP falsos. */
class BinanceSubcontaServiceTest {

    private final BinanceSubcontaRepository repositorio = mock(BinanceSubcontaRepository.class);
    private final BinanceLinkClient link = mock(BinanceLinkClient.class);
    private final BinanceStandardClient padrao = mock(BinanceStandardClient.class);
    private final Instant agora = Instant.parse("2026-10-21T12:00:00Z");
    private final Clock relogio = Clock.fixed(agora, ZoneOffset.UTC);

    @BeforeEach
    void preparar() {
        when(repositorio.findByUsuarioId(anyString())).thenReturn(Optional.empty());
        when(repositorio.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(link.precoBtcEmReais()).thenReturn(new BigDecimal("300000"));
    }

    private BinanceSubcontaService servico(String modo) {
        return new BinanceSubcontaService(repositorio, link, padrao, relogio, modo);
    }

    @Test
    void subcontaExistenteNaoChamaABinance() {
        BinanceSubconta existente = new BinanceSubconta("u1", "sub-1", "u1@x", "flecha-u1", agora);
        when(repositorio.findByUsuarioId("u1")).thenReturn(Optional.of(existente));

        assertThat(servico("LINK").obterOuCriar("u1")).isSameAs(existente);
        verifyNoInteractions(link, padrao);
    }

    @Test
    void modoLinkCriaSubcontaComTagLimpa() {
        when(link.criarSubconta("flecha-auth0abc123")).thenReturn(
                new BinanceLinkClient.SubcontaResposta("sub-9", "sub9@binance", "flecha-auth0abc123"));

        BinanceSubconta nova = servico("LINK").obterOuCriar("auth0|ABC123");

        assertThat(nova.getUsuarioId()).isEqualTo("auth0|ABC123");
        assertThat(nova.getSubaccountId()).isEqualTo("sub-9");
        assertThat(nova.getEmail()).isEqualTo("sub9@binance");
        assertThat(nova.getTag()).isEqualTo("flecha-auth0abc123");
        assertThat(nova.getCriadaEm()).isEqualTo(agora);
        verifyNoInteractions(padrao);
    }

    @Test
    void modoStandardUsaOEmailComoIdentificador() {
        when(padrao.criarSubconta(anyString())).thenReturn(new BinanceStandardClient.SubcontaResposta("v@virtual"));

        BinanceSubconta nova = servico("standard").obterOuCriar("google-oauth2|" + "1".repeat(40));

        assertThat(nova.getSubaccountId()).isEqualTo("v@virtual");
        assertThat(nova.getEmail()).isEqualTo("v@virtual");
        // tag limitada a 31 caracteres
        assertThat(nova.getTag()).startsWith("flecha-googleoauth2").hasSize(31);
        verify(link, never()).criarSubconta(anyString());
    }

    @Test
    void enviarBonusNoModoLinkTransfereOBtcCalculado() {
        when(link.criarSubconta(anyString())).thenReturn(new BinanceLinkClient.SubcontaResposta("sub-2", "e", "t"));
        when(link.transferirParaSubconta(eq("sub-2"), any(), anyString()))
                .thenReturn(new BinanceLinkClient.TransferenciaResposta(true, "tx", "ref"));

        BigDecimal btc = servico("LINK").enviarBonus("u2", new BigDecimal("100"));

        assertThat(btc.signum()).isPositive();
        assertThat(btc.scale()).isEqualTo(8);
        verify(link).transferirParaSubconta(eq("sub-2"), eq(btc), anyString());
    }

    @Test
    void enviarBonusNoModoLinkSemConfirmacaoFalha() {
        when(link.criarSubconta(anyString())).thenReturn(new BinanceLinkClient.SubcontaResposta("sub-3", "e", "t"));
        when(link.transferirParaSubconta(anyString(), any(), anyString()))
                .thenReturn(new BinanceLinkClient.TransferenciaResposta(false, null, null))
                .thenReturn(null);

        BinanceSubcontaService servico = servico("LINK");
        assertThatThrownBy(() -> servico.enviarBonus("u3", new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> servico.enviarBonus("u3", new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enviarBonusNoModoStandardTransferePeloEmail() {
        when(padrao.criarSubconta(anyString())).thenReturn(new BinanceStandardClient.SubcontaResposta("s@virtual"));
        when(padrao.transferirParaSubconta(eq("s@virtual"), any(), anyString()))
                .thenReturn(new BinanceStandardClient.TransferenciaResposta("123"));

        BigDecimal btc = servico("STANDARD").enviarBonus("u4", new BigDecimal("100"));

        // 100 moedas = R$ 100 (1:1) / R$ 300.000 por BTC
        assertThat(btc).isEqualByComparingTo("0.00033333");
        verify(padrao).transferirParaSubconta(eq("s@virtual"), eq(btc), anyString());
    }

    @Test
    void enviarBonusNoModoStandardSemConfirmacaoFalha() {
        when(padrao.criarSubconta(anyString())).thenReturn(new BinanceStandardClient.SubcontaResposta("s@virtual"));
        when(padrao.transferirParaSubconta(anyString(), any(), anyString()))
                .thenReturn(new BinanceStandardClient.TransferenciaResposta(null))
                .thenReturn(null);

        BinanceSubcontaService servico = servico("STANDARD");
        assertThatThrownBy(() -> servico.enviarBonus("u5", new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> servico.enviarBonus("u5", new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void valorPequenoDemaisNaoTransfere() {
        when(link.criarSubconta(anyString())).thenReturn(new BinanceLinkClient.SubcontaResposta("sub-6", "e", "t"));

        assertThatThrownBy(() -> servico("LINK").enviarBonus("u6", new BigDecimal("0.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insuficiente");
        verify(link, never()).transferirParaSubconta(anyString(), any(), anyString());
    }
}
