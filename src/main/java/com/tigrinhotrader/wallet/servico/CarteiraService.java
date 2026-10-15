package com.tigrinhotrader.wallet.servico;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.CarteiraRepository;
import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import com.tigrinhotrader.wallet.dominio.Transacao;
import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import com.tigrinhotrader.wallet.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.wallet.pagamento.GatewayPagamento;
import com.tigrinhotrader.wallet.pagamento.GatewayPagamento.Operacao;
import com.tigrinhotrader.wallet.pagamento.GatewayPagamento.ResultadoPagamento;
import com.tigrinhotrader.wallet.binance.BinanceSubcontaService;
import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CarteiraService {

    private static final Logger log = LoggerFactory.getLogger(CarteiraService.class);

    private final CarteiraRepository carteiras;
    private final TransacaoRepository transacoes;
    private final GatewayPagamento gatewayPagamento;
    private final Clock relogio;
    private final BigDecimal saldoInicial;
    private final BinanceSubcontaService binanceSubcontaService;
    private final boolean binanceHabilitada;

    public CarteiraService(CarteiraRepository carteiras, TransacaoRepository transacoes,
                           GatewayPagamento gatewayPagamento, Clock relogio,
                           @Value("${wallet.saldo-inicial:1000.00}") BigDecimal saldoInicial,
                           BinanceSubcontaService binanceSubcontaService,
                           @Value("${BINANCE_SUBCONTAS_HABILITADO:false}") boolean binanceHabilitada) {
        this.carteiras = carteiras;
        this.transacoes = transacoes;
        this.gatewayPagamento = gatewayPagamento;
        this.relogio = relogio;
        this.saldoInicial = saldoInicial;
        this.binanceSubcontaService = binanceSubcontaService;
        this.binanceHabilitada = binanceHabilitada;
    }

    /** Todo jogador novo ganha um bonus de fichas ficticias no primeiro acesso. */
    @Transactional
    public Carteira obterOuCriar(String usuarioId) {
        return carteiras.findById(usuarioId).orElseGet(() -> {
            Instant agora = relogio.instant();
            Carteira nova = carteiras.save(new Carteira(usuarioId, saldoInicial, agora));
            transacoes.save(new Transacao(usuarioId, TipoTransacao.BONUS_INICIAL, nova.getSaldo(), nova.getSaldo(),
                    null, "Bonus de boas-vindas", agora));
            return nova;
        });
    }

    @Transactional
    public List<Transacao> extrato(String usuarioId) {
        obterOuCriar(usuarioId);
        return transacoes.findByUsuarioIdOrderByCriadaEmDesc(usuarioId);
    }

    @Transactional
    public Carteira depositar(String usuarioId, BigDecimal valor) {
        Carteira carteira = obterOuCriar(usuarioId);
        ResultadoPagamento pagamento = aprovar(usuarioId, valor, Operacao.DEPOSITO);
        Instant agora = relogio.instant();
        carteira.creditar(valor, agora);
        registrar(carteira, TipoTransacao.DEPOSITO, valor, pagamento.idPagamento(), "Deposito sandbox", agora);
        return carteira;
    }

    @Transactional
    public Carteira sacar(String usuarioId, BigDecimal valor) {
        Carteira carteira = obterOuCriar(usuarioId);
        Instant agora = relogio.instant();
        if (binanceHabilitada) {
            if (valor == null || valor.signum() <= 0) throw new IllegalArgumentException("Valor precisa ser positivo");
            if (carteira.getSaldo().compareTo(valor) < 0) throw new SaldoInsuficienteException(carteira.getSaldo());
            BigDecimal btc = binanceSubcontaService.enviarBonus(usuarioId, valor);
            carteira.debitar(valor, agora);
            registrar(carteira, TipoTransacao.SAQUE, valor.negate(), "binance-btc-" + btc,
                    "Saque Binance: " + btc.toPlainString() + " BTC", agora);
            return carteira;
        }
        carteira.debitar(valor, agora);
        ResultadoPagamento pagamento = aprovar(usuarioId, valor, Operacao.SAQUE);
        registrar(carteira, TipoTransacao.SAQUE, valor.negate(), pagamento.idPagamento(), "Saque sandbox", agora);
        return carteira;
    }

    /**
     * Credita moedas compradas (Pix ja confirmado pelo provedor). Idempotente pela referencia (id da
     * cobranca): consultas repetidas nunca creditam duas vezes.
     *
     * @return false se a compra ja tinha sido creditada
     */
    @Transactional
    public boolean creditarCompra(String usuarioId, BigDecimal moedas, String referencia, String descricao) {
        if (transacoes.existsByReferencia(referencia)) {
            log.info("Compra {} ja creditada, ignorando", referencia);
            return false;
        }
        Carteira carteira = obterOuCriar(usuarioId);
        Instant agora = relogio.instant();
        carteira.creditar(moedas, agora);
        registrar(carteira, TipoTransacao.COMPRA_PIX, moedas, referencia, descricao, agora);
        return true;
    }

    /**
     * Aplica o lucro/prejuizo de uma rodada. Idempotente pelo ordemId: a fila entrega
     * pelo menos uma vez, entao o mesmo evento pode chegar repetido.
     *
     * @return false se o evento ja tinha sido aplicado
     */
    @Transactional
    public boolean aplicarResultadoRodada(OrdemExecutadaEvento evento) {
        String referencia = evento.ordemId().toString();
        if (transacoes.existsByReferencia(referencia)) {
            log.info("Rodada {} ja aplicada, ignorando evento repetido", referencia);
            return false;
        }
        Carteira carteira = obterOuCriar(evento.usuarioId());
        Instant agora = relogio.instant();
        BigDecimal aplicado = carteira.aplicarResultado(evento.valorLiquido(), agora);
        TipoTransacao tipo = switch (aplicado.signum()) {
            case 1 -> TipoTransacao.GANHO_RODADA;
            case -1 -> TipoTransacao.PERDA_RODADA;
            default -> TipoTransacao.EMPATE_RODADA;
        };
        registrar(carteira, tipo, aplicado, referencia,
                "Rodada " + evento.tipo() + " " + evento.simbolo() + ": " + evento.resultado(), agora);
        return true;
    }

    private ResultadoPagamento aprovar(String usuarioId, BigDecimal valor, Operacao operacao) {
        ResultadoPagamento resultado = gatewayPagamento.processar(usuarioId, valor, operacao);
        if (!resultado.aprovado()) {
            throw new RegraNegocioException(resultado.motivo());
        }
        return resultado;
    }

    private void registrar(Carteira carteira, TipoTransacao tipo, BigDecimal valor, String referencia,
                           String descricao, Instant agora) {
        carteiras.save(carteira);
        transacoes.save(new Transacao(carteira.getUsuarioId(), tipo, valor, carteira.getSaldo(), referencia,
                descricao, agora));
    }
}
