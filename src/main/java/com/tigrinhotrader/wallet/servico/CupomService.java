package com.tigrinhotrader.wallet.servico;

import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Cupom de moedas ({@code wallet.cupom.*}, env CUPOM_CODIGO e CUPOM_MOEDAS). Codigo comparado sem espacos nas
 * pontas e sem diferenciar maiusculas. Cada conta resgata uma vez so; codigo vazio ou moedas <= 0 = nenhum
 * cupom vale.
 *
 * <p>Sem {@code @Transactional} de proposito: o credito roda numa transacao do {@link CarteiraService} e, se
 * ela falhar por corrida (dois pedidos iguais ao mesmo tempo), aqui ja da para consultar o banco de novo.
 */
@Service
public class CupomService {

    /** Tamanho da coluna transacoes.referencia. */
    static final int TAMANHO_REFERENCIA = 64;
    private static final String PREFIXO = "cupom:";

    private final CarteiraService carteiras;
    private final TransacaoRepository transacoes;
    private final String codigo;
    private final BigDecimal moedas;

    public CupomService(CarteiraService carteiras, TransacaoRepository transacoes,
                        @Value("${wallet.cupom.codigo:}") String codigo,
                        @Value("${wallet.cupom.moedas:0}") BigDecimal moedas) {
        this.carteiras = carteiras;
        this.transacoes = transacoes;
        this.codigo = normalizar(codigo);
        // env vazia vira null
        this.moedas = moedas == null ? BigDecimal.ZERO : moedas.setScale(2, RoundingMode.HALF_UP);
    }

    /** Moedas creditadas e saldo da carteira depois do credito. */
    public record Resgate(BigDecimal moedas, BigDecimal saldo) {
    }

    /**
     * @throws RegraNegocioException "Cupom invalido" se o codigo nao confere ou nao ha cupom configurado
     * @throws CupomJaUsadoException se a conta ja resgatou este cupom
     */
    public Resgate resgatar(String usuarioId, String codigoInformado) {
        String informado = normalizar(codigoInformado);
        if (!habilitado() || !codigo.equals(informado)) {
            throw new RegraNegocioException("Cupom invalido");
        }
        String referencia = referencia(informado, usuarioId);
        try {
            Carteira carteira = carteiras.creditarCupom(usuarioId, moedas, referencia, "Cupom " + informado);
            return new Resgate(moedas, carteira.getSaldo());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            // Corrida: outro pedido desta conta gravou o cupom primeiro (referencia unica). Qualquer outra falha sobe.
            if (transacoes.existsByReferencia(referencia)) {
                throw new CupomJaUsadoException();
            }
            throw e;
        }
    }

    boolean habilitado() {
        return !codigo.isEmpty() && moedas.signum() > 0;
    }

    static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toUpperCase(Locale.ROOT);
    }

    /** {@code cupom:CODIGO:usuario}; se passar do tamanho da coluna, vira um hash estavel do mesmo par. */
    static String referencia(String codigoNormalizado, String usuarioId) {
        String chave = codigoNormalizado + ":" + usuarioId;
        String referencia = PREFIXO + chave;
        if (referencia.length() <= TAMANHO_REFERENCIA) {
            return referencia;
        }
        return PREFIXO + sha256(chave).substring(0, TAMANHO_REFERENCIA - PREFIXO.length());
    }

    private static String sha256(String texto) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 faz parte de toda JVM
            throw new IllegalStateException(e);
        }
    }
}
