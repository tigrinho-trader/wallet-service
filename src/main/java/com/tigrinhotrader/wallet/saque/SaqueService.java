package com.tigrinhotrader.wallet.saque;

import com.tigrinhotrader.wallet.dominio.SaldoInsuficienteException;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa;
import com.tigrinhotrader.wallet.repasse.RepasseException;
import com.tigrinhotrader.wallet.repasse.RepasseService;
import com.tigrinhotrader.wallet.saque.SaquePix.Status;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saque por Pix: troca moedas da carteira da plataforma por um Pix do caixa do Asaas. Precisa das DUAS coisas:
 * moedas na carteira (1 moeda = R$ 1) e dinheiro livre no caixa do Asaas (o saldo que nao veio das compras, ver
 * {@link RepasseService#caixaLivre()}). So para contas ADMIN ativas (o user-service confirma); para o resto, nao existe.
 *
 * <p>Nunca paga duas vezes nem paga sem moeda: o debito das moedas e o registro do saque sao gravados juntos,
 * numa transacao, ANTES de chamar o Asaas. Se o Pix certamente nao saiu, as moedas voltam (uma vez so); sem
 * resposta do Asaas, o saque fica para conferir e as moedas continuam debitadas.
 */
@Service
public class SaqueService {

    private static final Logger log = LoggerFactory.getLogger(SaqueService.class);
    /** Menor saque aceito. */
    static final BigDecimal MINIMO = new BigDecimal("1.00");
    /** Um saque igual (mesma chave e valor) dentro deste tempo e tratado como clique repetido. */
    static final Duration JANELA_REPETIDO = Duration.ofMinutes(2);
    static final String DESCRICAO = "Saque Tigrinho Trader";

    private final SaquePixRepository saques;
    private final CarteiraService carteiras;
    private final AsaasCaixa asaas;
    private final RepasseService repasse;
    private final ClienteUsuarios usuarios;
    private final Clock relogio;
    private final TransactionTemplate transacao;
    private final int centavosPorMoeda;

    public SaqueService(SaquePixRepository saques, CarteiraService carteiras, AsaasCaixa asaas, RepasseService repasse,
                        ClienteUsuarios usuarios, Clock relogio, PlatformTransactionManager transacoes,
                        @Value("${wallet.pix.centavos-por-moeda:100}") int centavosPorMoeda) {
        this.saques = saques;
        this.carteiras = carteiras;
        this.asaas = asaas;
        this.repasse = repasse;
        this.usuarios = usuarios;
        this.relogio = relogio;
        this.transacao = new TransactionTemplate(transacoes);
        this.centavosPorMoeda = centavosPorMoeda > 0 ? centavosPorMoeda : 100;
        log.info("Saque Pix {}", asaas.configurado() ? "ligado para as contas ADMIN" : "desligado (Asaas de producao nao configurado)");
    }

    /** Pode sacar: Asaas de producao configurado e conta ADMIN ativa (perguntado ao user-service a cada vez). */
    public boolean permitido(String usuarioId) {
        return asaas.configurado() && usuarioId != null && !usuarioId.isBlank() && usuarios.admin(usuarioId);
    }

    /**
     * @param disponivel quanto da para sacar agora: o menor entre as moedas (em reais) e o caixa livre do Asaas
     * @param moedas     saldo da carteira na plataforma
     * @param caixa      dinheiro livre no Asaas
     */
    public record Resumo(boolean permitido, BigDecimal disponivel, BigDecimal moedas, BigDecimal caixa,
                         BigDecimal minimo, List<SaquePix> recentes) {
        static Resumo negado() {
            return new Resumo(false, null, null, null, null, List.of());
        }
    }

    /** Moedas, caixa e os ultimos saques (os em aberto sao atualizados no Asaas). */
    public Resumo resumo(String usuarioId) {
        if (!permitido(usuarioId)) {
            return Resumo.negado();
        }
        List<SaquePix> recentes = saques.findTop20ByUsuarioIdOrderByCriadoEmDesc(usuarioId).stream()
                .map(s -> s.status.emAberto() ? atualizar(s) : s).toList();
        BigDecimal moedas = carteiras.obterOuCriar(usuarioId).getSaldo();
        BigDecimal caixa = repasse.caixaLivre();
        BigDecimal disponivel = reaisDe(moedas).min(caixa).max(BigDecimal.ZERO);
        return new Resumo(true, disponivel, moedas, caixa, MINIMO, recentes);
    }

    public synchronized SaquePix sacar(String usuarioId, String tipo, String chave, BigDecimal valor) {
        if (!permitido(usuarioId)) {
            throw new SaqueNaoPermitidoException();
        }
        ChavePix destino = ChavePix.de(tipo, chave);
        if (valor == null || valor.signum() <= 0 || valor.stripTrailingZeros().scale() > 2) {
            throw new RegraNegocioException("Valor invalido: use reais com ate 2 casas");
        }
        BigDecimal quanto = valor.setScale(2);
        if (quanto.compareTo(MINIMO) < 0) {
            throw new RegraNegocioException("O saque minimo e de R$ " + MINIMO);
        }
        BigDecimal moedas = moedasDe(quanto);
        BigDecimal saldoMoedas = carteiras.obterOuCriar(usuarioId).getSaldo();
        if (moedas.compareTo(saldoMoedas) > 0) {
            throw semMoedas(saldoMoedas);
        }
        Instant agora = relogio.instant();
        boolean repetido = saques.findTop20ByUsuarioIdOrderByCriadoEmDesc(usuarioId).stream()
                .anyMatch(s -> s.status != Status.FALHOU && s.chave.equals(destino.valor())
                        && s.valor.compareTo(quanto) == 0 && s.criadoEm.isAfter(agora.minus(JANELA_REPETIDO)));
        if (repetido) {
            throw new RegraNegocioException("Um saque igual acabou de ser feito. Confira a lista antes de repetir.");
        }
        // sob a trava do repasse: nenhum repasse comeca entre conferir o caixa e pedir o Pix
        return repasse.comCaixaLivre(livre -> {
            if (quanto.compareTo(livre) > 0) {
                throw new RegraNegocioException("O caixa do Asaas nao tem esse valor livre: R$ " + livre + " disponiveis");
            }
            SaquePix novo = new SaquePix(UUID.randomUUID(), usuarioId, destino, quanto, relogio.instant());
            SaquePix s;
            try {
                // debito das moedas + registro do saque: os dois ou nenhum, antes de qualquer dinheiro sair
                s = transacao.execute(t -> {
                    carteiras.debitarSaquePix(usuarioId, moedas, "saque-pix:" + novo.id,
                            "Saque Pix: R$ " + quanto + " para " + novo.chaveMascarada());
                    return saques.save(novo);
                });
            } catch (SaldoInsuficienteException e) {
                throw semMoedas(carteiras.obterOuCriar(usuarioId).getSaldo()); // gastou moedas no meio do caminho
            } catch (OptimisticLockingFailureException e) {
                // uma rodada mexeu na carteira no mesmo instante: nada foi debitado nem pago
                throw new RegraNegocioException("Sua carteira mudou agora mesmo. Confira o saldo e tente de novo.");
            }
            try {
                aplicar(s, asaas.transferirPix(destino.valor(), destino.tipo().name(), quanto, DESCRICAO, s.id.toString()));
                log.info("Saque {}: Pix de R$ {} para {} pedido ao Asaas ({})", s.id, quanto, s.chaveMascarada(), s.status);
            } catch (RepasseException e) {
                s.mudar(Status.FALHOU, "O Asaas recusou o Pix: " + e.getMessage(), relogio.instant());
                log.warn("Saque {}: recusado pelo Asaas ({}); moedas devolvidas", s.id, e.getMessage());
            } catch (RuntimeException e) {
                s.mudar(Status.CONFERIR, "Sem resposta do Asaas: confira no app do Asaas antes de tentar de novo",
                        relogio.instant());
                log.error("Saque {}: sem resposta do Asaas ({}); conferir antes de repetir", s.id, e.toString());
            }
            return gravar(s);
        });
    }

    private SaquePix atualizar(SaquePix s) {
        if (s.asaasId == null) {
            return s;
        }
        try {
            aplicar(s, asaas.consultarTransferencia(s.asaasId));
            return gravar(s);
        } catch (RuntimeException e) {
            log.debug("Saque {}: nao consegui atualizar no Asaas ({})", s.id, e.toString());
            return s;
        }
    }

    /** Grava o saque; se ele falhou (o Pix certamente nao saiu), devolve as moedas na mesma transacao. */
    private SaquePix gravar(SaquePix s) {
        return transacao.execute(t -> {
            if (s.status == Status.FALHOU) {
                carteiras.estornarSaquePix(s.usuarioId, moedasDe(s.valor), "estorno-saque-pix:" + s.id,
                        "Estorno: o Pix do saque de R$ " + s.valor + " nao saiu");
            }
            return saques.save(s);
        });
    }

    private void aplicar(SaquePix s, AsaasCaixa.Transferencia t) {
        if (t.id() != null) {
            s.asaasId = t.id();
        }
        s.statusAsaas = t.status();
        if (t.taxa() != null) {
            s.taxa = t.taxa();
        }
        if (t.destinatario() != null && !t.destinatario().isBlank()) {
            s.destinatario = t.destinatario().length() > 120 ? t.destinatario().substring(0, 120) : t.destinatario();
        }
        Status novo = t.concluida() ? Status.CONCLUIDO
                : t.falhou() ? Status.FALHOU
                : !t.autorizado() ? Status.AGUARDANDO_AUTORIZACAO
                : Status.ENVIADO;
        String motivo = novo == Status.FALHOU
                ? "O Asaas nao concluiu o Pix" + (t.motivo() == null || t.motivo().isBlank() ? "" : ": " + t.motivo())
                : null;
        s.mudar(novo, motivo, relogio.instant());
    }

    private RegraNegocioException semMoedas(BigDecimal saldoMoedas) {
        return new RegraNegocioException("Saldo insuficiente na plataforma: voce tem " + saldoMoedas
                + " moedas (R$ " + reaisDe(saldoMoedas) + ")");
    }

    /** Moedas que um saque de {@code reais} consome (arredonda para cima: nunca sai mais dinheiro que moeda). */
    BigDecimal moedasDe(BigDecimal reais) {
        return reais.movePointRight(2).divide(BigDecimal.valueOf(centavosPorMoeda), 2, RoundingMode.UP);
    }

    /** Reais que as moedas valem (arredonda para baixo). */
    BigDecimal reaisDe(BigDecimal moedas) {
        return moedas.multiply(BigDecimal.valueOf(centavosPorMoeda)).movePointLeft(2).setScale(2, RoundingMode.DOWN);
    }
}
