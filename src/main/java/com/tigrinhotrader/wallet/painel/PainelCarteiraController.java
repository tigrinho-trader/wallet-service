package com.tigrinhotrader.wallet.painel;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.CarteiraRepository;
import com.tigrinhotrader.wallet.dominio.Transacao;
import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import com.tigrinhotrader.wallet.repasse.RepasseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Painel do super-admin (pelo gateway em /api/painel/carteira/**): a carteira de poupanca e o saldo de cada perfil.
 * So responde com o cabecalho {@value #CABECALHO}, que o gateway poe depois de validar o login do painel.
 */
@RestController
@RequestMapping("/painel")
public class PainelCarteiraController {

    public static final String CABECALHO = "X-Painel-Admin";
    static final int ULTIMAS = 50;

    public record Movimento(BigDecimal valor, BigDecimal saldoApos, String descricao, Instant criadaEm) {
        static Movimento de(Transacao t) {
            return new Movimento(t.getValor(), t.getSaldoApos(), t.getDescricao(), t.getCriadaEm());
        }
    }

    /** Saldo da carteira de poupanca e os ultimos movimentos dela (o mais novo primeiro). */
    public record PoupancaResponse(BigDecimal saldo, List<Movimento> movimentos) {
    }

    public record CarteiraPerfil(String usuarioId, BigDecimal saldo, Instant atualizadaEm) {
    }

    private final CarteiraRepository carteiras;
    private final TransacaoRepository transacoes;
    private final RepasseService repasse;

    public PainelCarteiraController(CarteiraRepository carteiras, TransacaoRepository transacoes,
                                    RepasseService repasse) {
        this.carteiras = carteiras;
        this.transacoes = transacoes;
        this.repasse = repasse;
    }

    /** Repasse das compras de moedas para a Deriv: fila, por que esta parado e os ultimos repasses. */
    @GetMapping("/repasse")
    public RepasseService.Situacao repasse() {
        return repasse.situacao();
    }

    public record Liberacao(boolean liberado, RepasseService.Situacao situacao) {
    }

    /** "Conferi a falha": a ultima falha deixa de segurar os repasses novos. */
    @PostMapping("/repasse/liberar")
    public Liberacao liberarRepasse() {
        boolean liberado = repasse.liberarAposFalha();
        return new Liberacao(liberado, repasse.situacao());
    }

    @GetMapping("/poupanca")
    public PoupancaResponse poupanca() {
        BigDecimal saldo = carteiras.findById(Poupanca.CARTEIRA).map(Carteira::getSaldo).orElse(BigDecimal.ZERO);
        List<Movimento> movimentos = transacoes.findByUsuarioIdOrderByCriadaEmDesc(Poupanca.CARTEIRA).stream()
                .limit(ULTIMAS).map(Movimento::de).toList();
        return new PoupancaResponse(saldo, movimentos);
    }

    /** As carteiras dos perfis de jogo (sem a de poupanca), do maior saldo para o menor. */
    @GetMapping("/carteiras")
    public List<CarteiraPerfil> carteiras() {
        return carteiras.findAll(Sort.by(Sort.Direction.DESC, "saldo")).stream()
                .filter(c -> !Poupanca.CARTEIRA.equals(c.getUsuarioId()))
                .map(c -> new CarteiraPerfil(c.getUsuarioId(), c.getSaldo(), c.getAtualizadaEm()))
                .toList();
    }

    /** Recusa (403) qualquer /painel/** sem o cabecalho do gateway. */
    @Configuration
    static class Guarda implements WebMvcConfigurer, HandlerInterceptor {

        @Override
        public void addInterceptors(InterceptorRegistry registro) {
            registro.addInterceptor(this).addPathPatterns("/painel", "/painel/**");
        }

        @Override
        public boolean preHandle(HttpServletRequest pedido, HttpServletResponse resposta, Object handler)
                throws java.io.IOException {
            if (StringUtils.hasText(pedido.getHeader(CABECALHO))) {
                return true;
            }
            resposta.sendError(HttpStatus.FORBIDDEN.value(), "Só o painel do super-admin");
            return false;
        }
    }
}
