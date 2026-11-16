package com.tigrinhotrader.wallet.painel;

import com.tigrinhotrader.wallet.pix.PixProperties;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras da carteira configuradas pelo painel do super-admin, por cima das do ambiente: hoje o valor minimo da compra
 * de moedas por Pix (padrao R$ 30: abaixo disso o repasse para a corretora fica juntando ate o saque minimo da
 * Binance). Fica no banco e vale na hora.
 */
@Service
public class ConfiguracaoCarteiraService {

    /** Regras em vigor. */
    public record Config(int pixMinimoMoedas) {
    }

    /** O que o painel envia. Campo nulo = volta ao padrao. */
    public record Alteracao(Integer pixMinimoMoedas) {
    }

    private final ConfiguracaoCarteiraRepository repositorio;
    private final PixProperties pix;
    private final Clock relogio;

    public ConfiguracaoCarteiraService(ConfiguracaoCarteiraRepository repositorio, PixProperties pix, Clock relogio) {
        this.repositorio = repositorio;
        this.pix = pix;
        this.relogio = relogio;
    }

    public Config doAmbiente() {
        return new Config(pix.minimoMoedas());
    }

    @Transactional(readOnly = true)
    public Config atual() {
        Integer minimo = repositorio.findById(ConfiguracaoCarteira.ID).map(c -> c.pixMinimoMoedas).orElse(null);
        return new Config(minimo == null ? pix.minimoMoedas() : minimo);
    }

    /** Menor compra de moedas por Pix em vigor. */
    public int pixMinimoMoedas() {
        return atual().pixMinimoMoedas();
    }

    @Transactional
    public Config alterar(Alteracao a) {
        Integer minimo = a.pixMinimoMoedas();
        if (minimo != null && (minimo < 1 || minimo > pix.maximoMoedas())) {
            throw new RegraNegocioException("O valor minimo do Pix vai de R$ 1 a R$ " + pix.maximoMoedas());
        }
        ConfiguracaoCarteira c = repositorio.findById(ConfiguracaoCarteira.ID).orElseGet(ConfiguracaoCarteira::nova);
        c.pixMinimoMoedas = minimo;
        c.atualizadaEm = relogio.instant();
        repositorio.save(c);
        return atual();
    }
}
