package com.tigrinhotrader.wallet.web;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import com.tigrinhotrader.wallet.dominio.Transacao;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import com.tigrinhotrader.wallet.servico.CupomService;
import com.tigrinhotrader.wallet.binance.BinanceSubconta;
import com.tigrinhotrader.wallet.binance.BinanceSubcontaService;
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
    private final BinanceSubcontaService binanceSubcontaService;
    private final CupomService cupomService;

    public CarteiraController(CarteiraService carteiraService, BinanceSubcontaService binanceSubcontaService,
                              CupomService cupomService) {
        this.carteiraService = carteiraService;
        this.binanceSubcontaService = binanceSubcontaService;
        this.cupomService = cupomService;
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

    public record SubcontaResponse(String subaccountId, String email, String tag, Instant criadaEm) {
        static SubcontaResponse de(BinanceSubconta s) {
            return new SubcontaResponse(s.getSubaccountId(), s.getEmail(), s.getTag(), s.getCriadaEm());
        }
    }

    /** Codigo vazio ou ausente cai no 422 "Cupom invalido" do servico, igual a um codigo errado. */
    public record CupomRequest(String codigo) {
    }

    public record CupomResponse(BigDecimal moedas, BigDecimal saldo) {
    }

    /** 200 com as moedas creditadas e o saldo; 422 cupom invalido; 409 se a conta ja usou o cupom. */
    @PostMapping("/cupons")
    public CupomResponse resgatarCupom(@RequestHeader(CABECALHO_USUARIO) String usuarioId,
                                       @RequestBody CupomRequest pedido) {
        CupomService.Resgate resgate = cupomService.resgatar(usuarioId, pedido.codigo());
        return new CupomResponse(resgate.moedas(), resgate.saldo());
    }

    @GetMapping("/binance/subconta")
    public SubcontaResponse subconta(@RequestHeader(CABECALHO_USUARIO) String usuarioId) {
        return SubcontaResponse.de(binanceSubcontaService.obterOuCriar(usuarioId));
    }
}
