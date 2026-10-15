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
    private final Clock clock;

    public BinanceSubcontaService(BinanceSubcontaRepository repositorio, BinanceLinkClient binance, Clock clock) {
        this.repositorio = repositorio;
        this.binance = binance;
        this.clock = clock;
    }

    @Transactional
    public BinanceSubconta obterOuCriar(String usuarioId) {
        return repositorio.findByUsuarioId(usuarioId).orElseGet(() -> {
            String tag = tagPara(usuarioId);
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
        BinanceLinkClient.TransferenciaResposta transferencia = binance.transferirParaSubconta(
                subconta.getSubaccountId(), btc, UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        if (transferencia == null || !transferencia.success()) {
            throw new IllegalStateException("A Binance nao confirmou a transferencia");
        }
        return btc;
    }

    private static String tagPara(String usuarioId) {
        String limpo = usuarioId.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return ("flecha-" + limpo).substring(0, Math.min(31, 7 + limpo.length()));
    }
}
