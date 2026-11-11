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
                           @Value("${wallet.saldo-inicial:0}") BigDecimal saldoInicial,
                           BinanceSubcontaService binanceSubcontaService,
                           @Value("${BINANCE_SUBCONTAS_HABILITADO:false}") boolean binanceHabilitada) {
        this.carteiras = carteiras;
        this.transacoes = transacoes;
        this.gatewayPagamento = gatewayPagamento;
        this.relogio = relogio;
        // env vazia ou negativa = sem bonus
        this.saldoInicial = saldoInicial == null ? BigDecimal.ZERO : saldoInicial.max(BigDecimal.ZERO);
        this.binanceSubcontaService = binanceSubcontaService;
        this.binanceHabilitada = binanceHabilitada;
    }

    /**
     * No primeiro acesso cria a carteira com {@code wallet.saldo-inicial} moedas (env CARTEIRA_SALDO_INICIAL).
     * So vale para carteiras novas; com 0 nao ha bonus e nada entra no extrato.
     */
    @Transactional
    public Carteira obterOuCriar(String usuarioId) {
        return carteiras.findById(usuarioId).orElseGet(() -> {
            Instant agora = relogio.instant();
            Carteira nova = carteiras.save(new Carteira(usuarioId, saldoInicial, agora));
            if (nova.getSaldo().signum() > 0) {
                transacoes.save(new Transacao(usuarioId, TipoTransacao.BONUS_INICIAL, nova.getSaldo(), nova.getSaldo(),
                        null, "Bonus de boas-vindas", agora));
            }
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
     * Debita as moedas de um saque por Pix. Roda dentro da transacao que grava o saque: se faltar moeda, nada e
     * gravado e nenhum Pix e pedido. A referencia ({@code saque-pix:ID}) e unica: o mesmo saque nunca debita duas vezes.
     *
     * @throws SaldoInsuficienteException se a carteira tem menos moedas que o saque
     */
    @Transactional
    public Carteira debitarSaquePix(String usuarioId, BigDecimal moedas, String referencia, String descricao) {
        if (transacoes.existsByReferencia(referencia)) {
            throw new IllegalStateException("Saque ja debitado: " + referencia);
        }
        Carteira carteira = obterOuCriar(usuarioId);
        Instant agora = relogio.instant();
        carteira.debitar(moedas, agora);
        carteiras.save(carteira);
        transacoes.saveAndFlush(new Transacao(usuarioId, TipoTransacao.SAQUE_PIX, moedas.negate(), carteira.getSaldo(),
                referencia, descricao, agora));
        return carteira;
    }

    /**
     * Devolve as moedas de um saque cujo Pix certamente nao saiu. Idempotente pela referencia
     * ({@code estorno-saque-pix:ID}): devolve uma vez so.
     *
     * @return false se ja tinha devolvido
     */
    @Transactional
    public boolean estornarSaquePix(String usuarioId, BigDecimal moedas, String referencia, String descricao) {
        if (transacoes.existsByReferencia(referencia)) {
            return false;
        }
        Carteira carteira = obterOuCriar(usuarioId);
        Instant agora = relogio.instant();
        carteira.creditar(moedas, agora);
        carteiras.save(carteira);
        transacoes.saveAndFlush(new Transacao(usuarioId, TipoTransacao.ESTORNO_SAQUE_PIX, moedas, carteira.getSaldo(),
                referencia, descricao, agora));
        return true;
    }

    /**
     * Credita um cupom. Cada conta resgata uma vez: a referencia ({@code cupom:CODIGO:usuario}) e unica no banco.
     * O saveAndFlush grava a transacao aqui dentro, entao uma corrida com outro pedido igual aparece como
     * violacao de unicidade ao sair do metodo (quem chama trata, ver {@link CupomService}).
     *
     * @throws CupomJaUsadoException se a conta ja resgatou este cupom
     */
    @Transactional
    public Carteira creditarCupom(String usuarioId, BigDecimal moedas, String referencia, String descricao) {
        if (transacoes.existsByReferencia(referencia)) {
            throw new CupomJaUsadoException();
        }
        Carteira carteira = obterOuCriar(usuarioId);
        Instant agora = relogio.instant();
        carteira.creditar(moedas, agora);
        carteiras.save(carteira);
        transacoes.saveAndFlush(new Transacao(usuarioId, TipoTransacao.CUPOM, moedas, carteira.getSaldo(), referencia,
                descricao, agora));
        return carteira;
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
