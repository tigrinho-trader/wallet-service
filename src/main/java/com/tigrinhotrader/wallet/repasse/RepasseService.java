package com.tigrinhotrader.wallet.repasse;

import com.tigrinhotrader.wallet.repasse.Repasse.Etapa;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Repasse automatico do saldo do Asaas para a conta Options da Deriv:
 *
 * <pre>
 * Asaas --Pix--> Binance (BRL -> USDT) --saque BSC--> carteira ponte --BSC--> carteira Deriv --> conta Options (USD)
 * </pre>
 *
 * A cada ciclo avanca no maximo o repasse em andamento (um por vez) ou inicia um novo se o Asaas tiver saldo.
 * Regra de seguranca: antes de cada operacao com dinheiro a etapa "em curso" e gravada; se o servico parar
 * no meio dela, o repasse e marcado como FALHOU (para conferir) em vez de repetir — nunca paga duas vezes.
 * Falha com resposta do servico ({@link RepasseException}) e certa: vira FALHOU dizendo onde o dinheiro ficou.
 */
@Service
public class RepasseService {

    private static final Logger log = LoggerFactory.getLogger(RepasseService.class);
    private static final EnumSet<Etapa> FINALIZADAS = EnumSet.of(Etapa.CONCLUIDO, Etapa.FALHOU);
    private static final BigInteger WEI = BigInteger.TEN.pow(18);
    /** Folga sobre o saque minimo da Binance, para a cotacao mudar entre a conferencia e a compra. */
    private static final BigDecimal FOLGA_MINIMO = new BigDecimal("1.02");

    private final RepasseRepository repasses;
    private final AsaasCaixa asaas;
    private final BinanceCaixa binance;
    private final CarteiraBsc carteira;
    private final DerivCaixa deriv;
    private final RepasseProperties props;
    private final Clock relogio;
    private final boolean ativo;
    private volatile boolean pixNovo = true;    // comeca consultando
    private Instant ultimaConsulta = Instant.EPOCH;
    private String ultimaEspera = "";
    private Instant ultimoAvisoEspera = Instant.EPOCH;

    public RepasseService(RepasseRepository repasses, AsaasCaixa asaas, BinanceCaixa binance, CarteiraBsc carteira,
                          DerivCaixa deriv, RepasseProperties props, Clock relogio) {
        this.repasses = repasses;
        this.asaas = asaas;
        this.binance = binance;
        this.carteira = carteira;
        this.deriv = deriv;
        this.props = props;
        this.relogio = relogio;
        List<String> faltando = new ArrayList<>();
        if (!props.habilitado()) faltando.add("REPASSE_DERIV_HABILITADO=false");
        if (!asaas.configurado()) faltando.add("ASAAS_API_KEY (producao)");
        if (!binance.configurado()) faltando.add("REPASSE_BINANCE_API_KEY/SECRET");
        if (!carteira.configurada()) faltando.add("CARTEIRA_PONTE_CHAVE");
        if (!props.derivEndereco().startsWith("0x") || props.derivEndereco().length() != 42) faltando.add("DERIV_USDT_BSC_ADDRESS");
        if (!deriv.configurada()) faltando.add("REPASSE_DERIV_TOKEN/REPASSE_DERIV_APP_ID");
        this.ativo = faltando.isEmpty();
        if (ativo) {
            log.info("Repasse Asaas -> Deriv LIGADO (carteira ponte {}; minimo: saque minimo da Binance{})", carteira.endereco(),
                    props.minimoReais().signum() > 0 ? " ou R$ " + props.minimoReais() : "");
        } else {
            log.info("Repasse Asaas -> Deriv desligado: {}", String.join(", ", faltando));
        }
    }

    public boolean ativo() {
        return ativo;
    }

    /** Um Pix de compra foi confirmado: o proximo ciclo (em ate 15 s) ja confere o saldo e inicia o repasse. */
    public void pixRecebido() {
        pixNovo = true;
    }

    @Scheduled(fixedDelayString = "${wallet.repasse.intervalo-ms:60000}",
               initialDelayString = "${wallet.repasse.atraso-inicial-ms:60000}")
    public synchronized void ciclo() { // synchronized: nunca dois ciclos ao mesmo tempo
        if (!ativo) {
            return;
        }
        try {
            var emAndamento = repasses.findFirstByEtapaNotInOrderByCriadoEmAsc(FINALIZADAS);
            if (emAndamento.isPresent()) {
                avancar(emAndamento.get());
            } else if (pixNovo || !relogio.instant().isBefore(ultimaConsulta.plus(props.consultaOciosa()))) {
                pixNovo = false;
                ultimaConsulta = relogio.instant();
                iniciarSeHouverSaldo();
            }
        } catch (Exception e) {
            // sem resposta (rede): tenta de novo no proximo ciclo; etapas "em curso" viram FALHOU la
            log.warn("Repasse Deriv: tentativa sem sucesso ({}); tenta de novo no proximo ciclo", e.toString());
        }
    }

    void iniciarSeHouverSaldo() {
        var ultimo = repasses.findFirstByOrderByCriadoEmDesc();
        if (ultimo.isPresent() && ultimo.get().etapa == Etapa.FALHOU
                && ultimo.get().atualizadoEm.plus(props.esperaAposFalha()).isAfter(relogio.instant())) {
            return; // da tempo de conferir a falha antes de mover mais dinheiro
        }
        BigDecimal saldo = asaas.saldo().setScale(2, RoundingMode.DOWN);
        if (saldo.signum() <= 0 || saldo.compareTo(props.minimoReais()) < 0) {
            return;
        }
        BinanceCaixa.Rede rede = binance.redeBsc("USDT");
        BigDecimal usdtEstimado = saldo.divide(binance.preco("USDTBRL"), 8, RoundingMode.DOWN);
        if (usdtEstimado.compareTo(rede.minimo().multiply(FOLGA_MINIMO)) < 0) {
            log.info("Repasse Deriv: R$ {} no Asaas (~{} USDT) ainda abaixo do saque minimo da Binance ({} USDT); juntando",
                    saldo, usdtEstimado.setScale(2, RoundingMode.DOWN), rede.minimo());
            return;
        }
        if (!carteira.gasSuficiente()) {
            log.warn("Repasse Deriv: carteira ponte {} sem BNB para as taxas; mande um pouco de BNB para ela", carteira.endereco());
            return;
        }
        Repasse r = repasses.save(new Repasse(saldo, relogio.instant()));
        log.info("Repasse {}: iniciado com R$ {}", r.id, saldo);
        avancar(r);
    }

    void avancar(Repasse r) {
        if (Duration.between(r.criadoEm, relogio.instant()).compareTo(props.prazoMaximo()) > 0) {
            falhar(r, "passou do prazo de " + props.prazoMaximo().toHours() + "h parado em " + r.etapa);
            return;
        }
        switch (r.etapa) {
            case NOVO -> pagarDepositoNaBinance(r);
            case PIX_ENVIADO -> conferirPix(r);
            case AGUARDANDO_BRL -> comprarUsdt(r);
            case COMPRADO -> sacarParaCarteira(r);
            case AGUARDANDO_CARTEIRA -> enviarParaDeriv(r);
            case ENVIADO_DERIV -> conferirTransacao(r);
            case AGUARDANDO_DERIV, TRANSFERINDO -> transferirParaOptions(r); // idempotente pelo request_id
            case PAGANDO, COMPRANDO, SACANDO, ENVIANDO ->
                    falhar(r, "o servico parou no meio da etapa " + r.etapa + "; confira antes de repetir");
            default -> { }
        }
    }

    private void pagarDepositoNaBinance(Repasse r) {
        r.brlAntes = binance.livre("BRL");
        r.binanceOrdem = binance.pedirDepositoPix(r.valorReais);
        String codigo = esperarCodigoPix(r.binanceOrdem);
        if (codigo == null) {
            falhar(r, "a Binance nao devolveu o codigo Pix do pedido " + r.binanceOrdem + "; nada foi pago");
            return;
        }
        BigDecimal valorNoCodigo = CodigoPix.valor(codigo);
        if (valorNoCodigo != null && valorNoCodigo.compareTo(r.valorReais) != 0) {
            falhar(r, "o codigo Pix da Binance e de R$ " + valorNoCodigo + ", diferente de R$ " + r.valorReais + "; nada foi pago");
            return;
        }
        mudar(r, Etapa.PAGANDO);
        try {
            AsaasCaixa.Pix pix = asaas.pagarPix(codigo, r.valorReais, "Repasse Binance " + r.binanceOrdem);
            r.pixAsaasId = pix.id();
        } catch (RepasseException e) {
            falhar(r, "o Asaas recusou o Pix (" + e.getMessage() + "); o dinheiro continua no Asaas");
            return;
        }
        mudar(r, Etapa.PIX_ENVIADO);
        log.info("Repasse {}: Pix de R$ {} pedido ao Asaas para a Binance (pedido {})", r.id, r.valorReais, r.binanceOrdem);
    }

    private String esperarCodigoPix(String ordem) {
        for (int tentativa = 0; tentativa < 5; tentativa++) {
            String codigo = binance.codigoPix(ordem);
            if (codigo != null) {
                return codigo;
            }
            dormir(3000);
        }
        return null;
    }

    private void conferirPix(Repasse r) {
        AsaasCaixa.Pix pix = asaas.consultarPix(r.pixAsaasId);
        if (pix.concluido()) {
            mudar(r, Etapa.AGUARDANDO_BRL);
        } else if (pix.recusado()) {
            falhar(r, "o Asaas nao enviou o Pix (" + pix.status() + " " + (pix.motivo() == null ? "" : pix.motivo())
                    + "); o dinheiro continua no Asaas");
        } else if (pix.aguardandoAutorizacao()) {
            log.warn("Repasse {}: o Pix aguarda autorizacao no app do Asaas (evento critico de saque ligado)", r.id);
        } else {
            aguardando(r, "Pix no Asaas com status " + pix.status());
        }
    }

    private void comprarUsdt(Repasse r) {
        BigDecimal brl = binance.livre("BRL");
        if (brl.compareTo(r.brlAntes.add(r.valorReais).subtract(new BigDecimal("0.01"))) < 0) {
            aguardando(r, "a Binance creditar os reais (R$ " + brl.subtract(r.brlAntes).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.DOWN) + " de R$ " + r.valorReais + ")");
            return;
        }
        mudar(r, Etapa.COMPRANDO);
        try {
            r.usdtComprado = binance.comprarUsdt(r.valorReais, r.id.toString());
        } catch (RepasseException e) {
            falhar(r, "a Binance recusou a compra de USDT (" + e.getMessage() + "); os reais estao na Binance");
            return;
        }
        mudar(r, Etapa.COMPRADO);
        log.info("Repasse {}: comprados {} USDT", r.id, r.usdtComprado);
        sacarParaCarteira(r);
    }

    private void sacarParaCarteira(Repasse r) {
        r.taxaSaque = binance.redeBsc("USDT").taxa();
        r.carteiraUsdtAntesWei = carteira.saldoUsdtWei().toString();
        BigDecimal quantidade = r.usdtComprado.setScale(2, RoundingMode.DOWN); // a taxa sai de dentro dela
        mudar(r, Etapa.SACANDO);
        try {
            r.saqueId = binance.sacarAutocustodia("USDT", quantidade, carteira.endereco());
        } catch (RepasseException e) {
            falhar(r, "a Binance recusou o saque (" + e.getMessage() + "); o USDT esta na Binance");
            return;
        }
        r.usdtComprado = quantidade;
        mudar(r, Etapa.AGUARDANDO_CARTEIRA);
        log.info("Repasse {}: saque de {} USDT para a carteira ponte aceito ({})", r.id, quantidade, r.saqueId);
    }

    private void enviarParaDeriv(Repasse r) {
        BigInteger esperado = new BigInteger(r.carteiraUsdtAntesWei)
                .add(r.usdtComprado.subtract(r.taxaSaque).movePointRight(18).toBigInteger())
                .subtract(BigInteger.TEN.pow(14)); // tolerancia de 0,0001 USDT
        BigInteger saldo = carteira.saldoUsdtWei();
        if (saldo.compareTo(esperado) < 0) {
            aguardando(r, "o saque da Binance chegar na carteira ponte");
            return;
        }
        r.derivSaldoAntes = deriv.carteiraPrincipal().saldo();
        r.usdtEnviado = new BigDecimal(saldo).divide(new BigDecimal(WEI));
        mudar(r, Etapa.ENVIANDO);
        try {
            r.txHash = carteira.enviarUsdt(props.derivEndereco(), saldo); // tudo: a carteira ponte nao guarda saldo
        } catch (RepasseException e) {
            falhar(r, "a rede recusou o envio (" + e.getMessage() + "); o USDT continua na carteira ponte");
            return;
        }
        mudar(r, Etapa.ENVIADO_DERIV);
        log.info("Repasse {}: {} USDT enviados para a Deriv (https://bscscan.com/tx/{})", r.id, r.usdtEnviado, r.txHash);
    }

    private void conferirTransacao(Repasse r) {
        var confirmacao = carteira.confirmada(r.txHash);
        if (confirmacao.isEmpty()) {
            aguardando(r, "a rede BSC confirmar a transacao " + r.txHash);
        }
        confirmacao.ifPresent(sucesso -> {
            if (sucesso) {
                mudar(r, Etapa.AGUARDANDO_DERIV);
            } else {
                falhar(r, "a transacao " + r.txHash + " falhou na rede; o USDT continua na carteira ponte");
            }
        });
    }

    private void transferirParaOptions(Repasse r) {
        if (r.etapa == Etapa.AGUARDANDO_DERIV) {
            BigDecimal minimoNovo = r.usdtEnviado.multiply(new BigDecimal("0.99"));
            if (deriv.carteiraPrincipal().saldo().compareTo(r.derivSaldoAntes.add(minimoNovo)) < 0) {
                aguardando(r, "a Deriv creditar o deposito na carteira");
                return;
            }
            mudar(r, Etapa.TRANSFERINDO);
        }
        UUID requestId = UUID.nameUUIDFromBytes(("options-" + r.id).getBytes(StandardCharsets.UTF_8));
        r.valorOptions = deriv.transferirParaOptions(requestId);
        mudar(r, Etapa.CONCLUIDO);
        log.info("Repasse {}: CONCLUIDO. R$ {} viraram ~{} USD na conta Options", r.id, r.valorReais, r.valorOptions);
    }

    private void falhar(Repasse r, String motivo) {
        String onde = r.etapa.ondeEstaODinheiro == null ? "confira Asaas, Binance, carteira ponte e Deriv"
                : "o dinheiro esta em: " + r.etapa.ondeEstaODinheiro;
        r.erro = (motivo + " | " + onde).substring(0, Math.min(500, (motivo + " | " + onde).length()));
        mudar(r, Etapa.FALHOU);
        log.error("Repasse {}: FALHOU - {}", r.id, r.erro);
    }

    /** Log de espera: na primeira vez e depois no maximo uma vez por minuto. */
    private void aguardando(Repasse r, String oQue) {
        int parenteses = oQue.indexOf(" (");
        String chave = r.id + (parenteses < 0 ? oQue : oQue.substring(0, parenteses)); // ignora o valor que muda
        Instant agora = relogio.instant();
        if (!chave.equals(ultimaEspera) || !agora.isBefore(ultimoAvisoEspera.plusSeconds(60))) {
            log.info("Repasse {}: aguardando {} (ha {} s)", r.id, oQue, Duration.between(r.atualizadoEm, agora).toSeconds());
            ultimaEspera = chave;
            ultimoAvisoEspera = agora;
        }
    }

    private void mudar(Repasse r, Etapa etapa) {
        log.info("Repasse {}: {} -> {}", r.id, r.etapa, etapa);
        r.etapa = etapa;
        r.atualizadoEm = relogio.instant();
        repasses.save(r);
    }

    void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
