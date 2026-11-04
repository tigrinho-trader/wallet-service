package com.tigrinhotrader.wallet.pix;

import com.tigrinhotrader.wallet.repasse.RepasseService;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compra de moedas por Pix: cria a cobranca no provedor, consulta o status e credita a carteira quando o
 * provedor confirma o pagamento. O cliente nunca diz "paguei": o servidor e quem pergunta ao provedor.
 */
@Service
public class CompraPixService {

    private final CompraPixRepository compras;
    private final ProvedorPix provedor;
    private final CarteiraService carteiras;
    private final PixProperties propriedades;
    private final Clock relogio;
    private final RepasseService repasse;

    public CompraPixService(CompraPixRepository compras, ProvedorPix provedor, CarteiraService carteiras,
                            PixProperties propriedades, Clock relogio, RepasseService repasse) {
        this.compras = compras;
        this.provedor = provedor;
        this.carteiras = carteiras;
        this.propriedades = propriedades;
        this.relogio = relogio;
        this.repasse = repasse;
    }

    public boolean habilitado() {
        return provedor.configurado();
    }

    /** Cria a cobranca de um dos pacotes permitidos. */
    @Transactional
    public CompraPix iniciar(String usuarioId, int moedas) {
        if (!habilitado()) {
            throw new PixIndisponivelException("Compra por Pix desligada (ASAAS_API_KEY nao configurada)");
        }
        if (moedas < propriedades.minimoMoedas() || moedas > propriedades.maximoMoedas()) {
            throw new RegraNegocioException("Quantidade invalida: compre de " + propriedades.minimoMoedas()
                    + " a " + propriedades.maximoMoedas() + " moedas");
        }
        UUID id = UUID.randomUUID();
        long centavos = (long) moedas * propriedades.centavosPorMoeda();
        ProvedorPix.CobrancaPix cobranca = provedor.criar(id.toString(), centavos,
                "Flecha Certeira: " + moedas + " moedas", propriedades.expiraEmSegundos());
        if (cobranca.id() == null || cobranca.id().isBlank()) {
            throw new PixIndisponivelException("Provedor Pix nao devolveu o id da cobranca");
        }
        return compras.save(new CompraPix(id, usuarioId, moedas, cobranca, relogio.instant()));
    }

    /** Devolve a compra do usuario, conferindo no provedor se ainda estiver pendente. */
    @Transactional
    public CompraPix consultar(String usuarioId, UUID id) {
        CompraPix compra = buscar(usuarioId, id);
        if (compra.pendente()) {
            ProvedorPix.StatusCobranca status = provedor.consultar(compra.getCobrancaId());
            // Passou da validade e ninguem pagou: cancela no provedor para o QR nao aceitar mais
            // pagamento (o Asaas mantem o QR valido por mais tempo que a loja).
            if (status == ProvedorPix.StatusCobranca.PENDENTE && venceu(compra)
                    && provedor.cancelar(compra.getCobrancaId())) {
                status = ProvedorPix.StatusCobranca.EXPIRADA;
            }
            aplicar(compra, status);
        }
        return compra;
    }

    /** Folga depois da validade para nao cancelar um Pix que acabou de ser enviado. */
    private static final java.time.Duration FOLGA_EXPIRACAO = java.time.Duration.ofMinutes(2);

    private boolean venceu(CompraPix compra) {
        return compra.getExpiraEm() != null
                && relogio.instant().isAfter(compra.getExpiraEm().plus(FOLGA_EXPIRACAO));
    }

    /** Paga a cobranca de mentira. So existe no modo de testes do provedor (sandbox do Asaas). */
    @Transactional
    public CompraPix simularPagamento(String usuarioId, UUID id) {
        CompraPix compra = buscar(usuarioId, id);
        if (!compra.isDevMode()) {
            throw new RegraNegocioException("Simulacao de pagamento so existe em modo de desenvolvimento");
        }
        if (compra.pendente()) {
            provedor.simularPagamento(compra.getCobrancaId());
            aplicar(compra, provedor.consultar(compra.getCobrancaId()));
        }
        return compra;
    }

    private CompraPix buscar(String usuarioId, UUID id) {
        return compras.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new CompraNaoEncontradaException(id));
    }

    private void aplicar(CompraPix compra, ProvedorPix.StatusCobranca status) {
        Instant agora = relogio.instant();
        if (compra.atualizarStatus(status, agora)) {
            carteiras.creditarCompra(compra.getUsuarioId(), BigDecimal.valueOf(compra.getMoedas()),
                    compra.getCobrancaId(), "Compra Pix: " + compra.getMoedas() + " moedas");
            repasse.pixRecebido(); // o dinheiro ja esta no Asaas: o repasse para a Deriv comeca em ate 15 s
        }
        compras.save(compra);
    }
}
