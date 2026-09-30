package com.tigrinhotrader.wallet.web;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import com.tigrinhotrader.wallet.dominio.Transacao;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** O usuario vem no cabecalho X-Usuario-Id, preenchido pelo api-gateway a partir do JWT. */
@RestController
@RequestMapping("/carteira")
public class CarteiraController {

    static final String CABECALHO_USUARIO = "X-Usuario-Id";

    private final CarteiraService carteiraService;

    public CarteiraController(CarteiraService carteiraService) {
        this.carteiraService = carteiraService;
    }

    public record CarteiraResponse(String usuarioId, BigDecimal saldo, Instant atualizadaEm) {
        static CarteiraResponse de(Carteira c) {
            return new CarteiraResponse(c.getUsuarioId(), c.getSaldo(), c.getAtualizadaEm());
        }
    }

    public record TransacaoResponse(UUID id, TipoTransacao tipo, BigDecimal valor, BigDecimal saldoApos,
                                    String referencia, String descricao, Instant criadaEm) {
        static TransacaoResponse de(Transacao t) {
            return new TransacaoResponse(t.getId(), t.getTipo(), t.getValor(), t.getSaldoApos(), t.getReferencia(),
                    t.getDescricao(), t.getCriadaEm());
        }
    }

    public record OperacaoRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal valor) {
    }

    @GetMapping
    public CarteiraResponse consultar(@RequestHeader(CABECALHO_USUARIO) String usuarioId) {
        return CarteiraResponse.de(carteiraService.obterOuCriar(usuarioId));
    }

    @GetMapping("/extrato")
    public List<TransacaoResponse> extrato(@RequestHeader(CABECALHO_USUARIO) String usuarioId) {
        return carteiraService.extrato(usuarioId).stream().map(TransacaoResponse::de).toList();
    }

    @PostMapping("/depositos")
    public CarteiraResponse depositar(@RequestHeader(CABECALHO_USUARIO) String usuarioId,
                                      @Valid @RequestBody OperacaoRequest pedido) {
        return CarteiraResponse.de(carteiraService.depositar(usuarioId, pedido.valor()));
    }

    @PostMapping("/saques")
    public CarteiraResponse sacar(@RequestHeader(CABECALHO_USUARIO) String usuarioId,
                                  @Valid @RequestBody OperacaoRequest pedido) {
        return CarteiraResponse.de(carteiraService.sacar(usuarioId, pedido.valor()));
    }
}
