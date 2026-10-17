package com.tigrinhotrader.wallet.binance;

import java.time.Clock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BinanceSubcontaService {
    private final BinanceSubcontaRepository repositorio;
    private final BinanceLinkClient binance;
    private final BinanceStandardClient binancePadrao;
    private final Clock clock;
    private final String modo;

    public BinanceSubcontaService(BinanceSubcontaRepository repositorio, BinanceLinkClient binance,
                                  BinanceStandardClient binancePadrao, Clock clock,
                                  @org.springframework.beans.factory.annotation.Value("${BINANCE_SUBCONTAS_MODO:LINK}") String modo) {
        this.repositorio = repositorio;
        this.binance = binance;
        this.binancePadrao = binancePadrao;
        this.clock = clock;
        this.modo = modo;
    }

    @Transactional
    public BinanceSubconta obterOuCriar(String usuarioId) {
        return repositorio.findByUsuarioId(usuarioId).orElseGet(() -> {
            String tag = tagPara(usuarioId);
            if (modo.equalsIgnoreCase("STANDARD")) {
                BinanceStandardClient.SubcontaResposta resposta = binancePadrao.criarSubconta(tag);
                return repositorio.save(new BinanceSubconta(usuarioId, resposta.email(), resposta.email(),
                        tag, clock.instant()));
            }
            BinanceLinkClient.SubcontaResposta resposta = binance.criarSubconta(tag);
            return repositorio.save(new BinanceSubconta(usuarioId, resposta.subaccountId(), resposta.email(),
                    resposta.tag(), clock.instant()));
        });
    }

    public BigDecimal enviarBonus(String usuarioId, BigDecimal moedas) {
        BinanceSubconta subconta = obterOuCriar(usuarioId);
        BigDecimal brl = moedas.divide(BigDecimal.TEN, 2, RoundingMode.DOWN);
        BigDecimal btc = brl.divide(binance.precoBtcEmReais(), 8, RoundingMode.DOWN);
        if (btc.signum() <= 0) throw new IllegalArgumentException("Valor insuficiente para enviar BTC");
        String referencia = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        if (modo.equalsIgnoreCase("STANDARD")) {
            BinanceStandardClient.TransferenciaResposta transferencia = binancePadrao.transferirParaSubconta(
                    subconta.getEmail(), btc, referencia);
            if (transferencia == null || transferencia.tranId() == null) {
                throw new IllegalStateException("A Binance nao confirmou a transferencia");
            }
        } else {
            BinanceLinkClient.TransferenciaResposta transferencia = binance.transferirParaSubconta(
                    subconta.getSubaccountId(), btc, referencia);
            if (transferencia == null || !transferencia.success()) {
                throw new IllegalStateException("A Binance nao confirmou a transferencia");
            }
        }
        return btc;
    }

    private static String tagPara(String usuarioId) {
        String limpo = usuarioId.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return ("flecha-" + limpo).substring(0, Math.min(31, 7 + limpo.length()));
    }
}
