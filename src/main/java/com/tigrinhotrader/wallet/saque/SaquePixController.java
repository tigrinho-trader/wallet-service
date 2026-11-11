package com.tigrinhotrader.wallet.saque;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/**
 * Saque por Pix do caixa do Asaas. O gateway apaga os X-Usuario-* do cliente e preenche com os do token, entao o
 * id aqui e confiavel. Quem nao e ADMIN recebe {@code permitido: false} no GET e 404 no POST.
 */
@RestController
@RequestMapping("/carteira/saques-pix")
public class SaquePixController {

    static final String CABECALHO_USUARIO = "X-Usuario-Id";

    private final SaqueService servico;

    public SaquePixController(SaqueService servico) {
        this.servico = servico;
    }

    public record SaqueRequest(
            @NotBlank String tipoChave,
            @NotBlank @Size(max = 80) String chave,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal valor) {
    }

    public record SaqueResponse(UUID id, BigDecimal valor, String tipoChave, String chave, String status,
                                String destinatario, BigDecimal taxa, String motivo, Instant criadoEm) {
        static SaqueResponse de(SaquePix s) {
            return new SaqueResponse(s.id, s.valor, s.tipoChave.name(), s.chaveMascarada(), s.status.name(),
                    s.destinatario, s.taxa, s.motivo, s.criadoEm);
        }
    }

    /**
     * {@code disponivel} = o menor entre as moedas (em reais) e o caixa livre do Asaas: o que da para sacar agora.
     */
    public record ResumoResponse(boolean permitido, BigDecimal disponivel, BigDecimal moedas, BigDecimal caixa,
                                 BigDecimal minimo, List<SaqueResponse> saques) {
    }

    @GetMapping
    public ResumoResponse resumo(@RequestHeader(CABECALHO_USUARIO) String usuarioId) {
        SaqueService.Resumo r = servico.resumo(usuarioId);
        return new ResumoResponse(r.permitido(), r.disponivel(), r.moedas(), r.caixa(), r.minimo(),
                r.recentes().stream().map(SaqueResponse::de).toList());
    }

    @PostMapping
    public SaqueResponse sacar(@RequestHeader(CABECALHO_USUARIO) String usuarioId,
                               @Valid @RequestBody SaqueRequest pedido) {
        return SaqueResponse.de(servico.sacar(usuarioId, pedido.tipoChave(), pedido.chave(), pedido.valor()));
    }
}
