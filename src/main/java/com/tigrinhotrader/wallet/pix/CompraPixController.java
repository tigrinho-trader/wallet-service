package com.tigrinhotrader.wallet.pix;

import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Compra de moedas por Pix. O jogador vem no cabecalho X-Usuario-Id, preenchido pelo api-gateway. */
@RestController
@RequestMapping("/carteira/compras")
public class CompraPixController {

    static final String CABECALHO_USUARIO = "X-Usuario-Id";

    private final CompraPixService compras;
    private final CarteiraService carteiras;
    private final PixProperties propriedades;
    private final boolean sandboxHabilitado;
    private final int limiteSandbox;

    public CompraPixController(CompraPixService compras, CarteiraService carteiras, PixProperties propriedades,
                               @Value("${wallet.sandbox.habilitado:true}") boolean sandboxHabilitado,
                               @Value("${wallet.sandbox.limite-por-operacao:5000.00}") BigDecimal limiteSandbox) {
        this.compras = compras;
        this.carteiras = carteiras;
        this.propriedades = propriedades;
        this.sandboxHabilitado = sandboxHabilitado;
        this.limiteSandbox = limiteSandbox.intValue();
    }

    /**
     * O que o frontend precisa para montar a loja de moedas. {@code minimoMoedas}/{@code maximoMoedas} valem
     * para o modo ativo: os limites do Pix, ou o limite do deposito de teste quando o Pix esta desligado.
     */
    public record ConfigResponse(boolean pixHabilitado, boolean sandboxHabilitado, List<Integer> pacotes,
                                 int centavosPorMoeda, int minimoMoedas, int maximoMoedas) {
    }

    public record NovaCompraRequest(@NotNull Integer moedas) {
    }

    /** {@code saldo} so vem quando a compra esta paga (saldo da carteira ja com as moedas). */
    public record CompraResponse(UUID id, int moedas, long centavos, StatusCobranca status, String brCode,
                                 String brCodeBase64, Instant expiraEm, boolean devMode, Instant criadaEm,
                                 Instant pagaEm, BigDecimal saldo) {
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        boolean pix = compras.habilitado();
        return new ConfigResponse(pix, sandboxHabilitado, propriedades.pacotes(), propriedades.centavosPorMoeda(),
                pix ? propriedades.minimoMoedas() : 1, pix ? propriedades.maximoMoedas() : limiteSandbox);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompraResponse iniciar(@RequestHeader(CABECALHO_USUARIO) String usuarioId,
                                  @Valid @RequestBody NovaCompraRequest pedido) {
        return resposta(compras.iniciar(usuarioId, pedido.moedas()));
    }

    @GetMapping("/{id}")
    public CompraResponse consultar(@RequestHeader(CABECALHO_USUARIO) String usuarioId, @PathVariable UUID id) {
        return resposta(compras.consultar(usuarioId, id));
    }

    @PostMapping("/{id}/simular-pagamento")
    public CompraResponse simular(@RequestHeader(CABECALHO_USUARIO) String usuarioId, @PathVariable UUID id) {
        return resposta(compras.simularPagamento(usuarioId, id));
    }

    private CompraResponse resposta(CompraPix c) {
        BigDecimal saldo = c.getStatus() == StatusCobranca.PAGA
                ? carteiras.obterOuCriar(c.getUsuarioId()).getSaldo()
                : null;
        return new CompraResponse(c.getId(), c.getMoedas(), c.getCentavos(), c.getStatus(), c.getBrCode(),
                c.getBrCodeBase64(), c.getExpiraEm(), c.isDevMode(), c.getCriadaEm(), c.getPagaEm(), saldo);
    }
}
