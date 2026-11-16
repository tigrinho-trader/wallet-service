package com.tigrinhotrader.wallet.painel;

import com.fasterxml.jackson.databind.JsonNode;
import com.tigrinhotrader.wallet.dominio.Carteira;
import com.tigrinhotrader.wallet.dominio.CarteiraRepository;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa;
import com.tigrinhotrader.wallet.repasse.BinanceCaixa;
import com.tigrinhotrader.wallet.repasse.CarteiraBsc;
import com.tigrinhotrader.wallet.repasse.DerivCaixa;
import com.tigrinhotrader.wallet.repasse.RepasseService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Painel financeiro do super-admin: quanto dinheiro ha no Asaas, na Binance, na carteira ponte e na Deriv, o que
 * entrou e saiu no periodo e o lado do jogo (moedas dos perfis, poupanca, fila do repasse). Cada lugar e consultado
 * separado: se um nao responde, os outros aparecem e ele vem com o erro. Guarda o resultado por 20 s.
 */
@Service
public class FinanceiroService {

    private static final Logger log = LoggerFactory.getLogger(FinanceiroService.class);
    static final Duration CACHE = Duration.ofSeconds(20);
    static final int ULTIMOS = 30;
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final BigInteger WEI = BigInteger.TEN.pow(18);

    /** Um movimento: quando, o que, quanto (+ entrou, - saiu), em que moeda e a situacao. */
    public record Movimento(String quando, String descricao, BigDecimal valor, String moeda, String situacao) {
    }

    /** Uma parte do painel: os dados ou, se o lugar nao respondeu, o erro. */
    public record Parte<T>(T dados, String erro) {
        static <T> Parte<T> ok(T dados) {
            return new Parte<>(dados, null);
        }

        static <T> Parte<T> falhou(String erro) {
            return new Parte<>(null, erro);
        }
    }

    /** Asaas: saldo, quanto e livre para saques (fora o que vai para a corretora) e o fluxo do periodo. */
    public record Asaas(BigDecimal saldo, BigDecimal caixaLivre, BigDecimal entradas, BigDecimal saidas,
                        BigDecimal taxas, List<Movimento> movimentos) {
    }

    /** Binance: saldos e o fluxo do periodo (Pix em reais, compras de USDT, saques de USDT). */
    public record Binance(BigDecimal brl, BigDecimal usdt, BigDecimal bnb, BigDecimal depositosBrl,
                          BigDecimal saquesBrl, BigDecimal usdtComprado, BigDecimal reaisEmUsdt, BigDecimal usdtSacado,
                          List<Movimento> movimentos) {
    }

    /** Carteira ponte (BSC): o USDT parado (deveria ser zero) e o BNB do gas. */
    public record Ponte(String endereco, BigDecimal usdt, BigDecimal bnb, boolean gasOk) {
    }

    /** Deriv: o USDT na carteira (ainda nao passou para a Options) e o saldo da conta Options. */
    public record Deriv(BigDecimal carteiraUsdt, Map<String, BigDecimal> carteira, BigDecimal options,
                        String moedaOptions) {
    }

    /** O lado do jogo: moedas dos perfis (o que o sistema deve a eles), poupanca e a fila do repasse. */
    public record Jogo(BigDecimal moedasPerfis, int perfis, BigDecimal poupanca, int naFilaRepasse,
                       BigDecimal valorFilaRepasse, String repasse) {
    }

    /**
     * Tudo junto. {@code totalReais}: Asaas + reais da Binance + todo o USDT (Binance, ponte, Deriv) e a Options pela
     * cotacao USDTBRL (a Options e em USD, aproximado). {@code sobra}: total - moedas dos perfis (1 moeda = R$ 1).
     */
    public record Financeiro(Instant em, int dias, BigDecimal cotacaoUsdt, BigDecimal totalReais, BigDecimal sobra,
                             Parte<Asaas> asaas, Parte<Binance> binance, Parte<Ponte> ponte, Parte<Deriv> deriv,
                             Jogo jogo) {
    }

    private final AsaasCaixa asaas;
    private final BinanceCaixa binance;
    private final CarteiraBsc carteira;
    private final DerivCaixa deriv;
    private final RepasseService repasse;
    private final CarteiraRepository carteiras;
    private final Clock relogio;
    private volatile Financeiro guardado;

    public FinanceiroService(AsaasCaixa asaas, BinanceCaixa binance, CarteiraBsc carteira, DerivCaixa deriv,
                             RepasseService repasse, CarteiraRepository carteiras, Clock relogio) {
        this.asaas = asaas;
        this.binance = binance;
        this.carteira = carteira;
        this.deriv = deriv;
        this.repasse = repasse;
        this.carteiras = carteiras;
        this.relogio = relogio;
    }

    public synchronized Financeiro financeiro(int dias) {
        int periodo = Math.max(1, Math.min(90, dias));
        Instant agora = relogio.instant();
        Financeiro anterior = guardado;
        if (anterior != null && anterior.dias() == periodo && anterior.em().plus(CACHE).isAfter(agora)) {
            return anterior;
        }
        Instant desde = agora.atZone(FUSO).toLocalDate().minusDays(periodo - 1L).atStartOfDay(FUSO).toInstant();
        BigDecimal cotacao = tentar("cotacao", () -> binance.configurado() ? binance.preco("USDTBRL") : null).dados();
        Parte<Asaas> a = asaas.configurado() ? tentar("Asaas", () -> asaas(desde)) : naoConfigurado();
        Parte<Binance> b = binance.configurado() ? tentar("Binance", () -> binance(desde)) : naoConfigurado();
        Parte<Ponte> p = carteira.configurada() ? tentar("carteira ponte", this::ponte) : naoConfigurado();
        Parte<Deriv> d = deriv.configurada() ? tentar("Deriv", this::deriv) : naoConfigurado();
        Jogo jogo = jogo();

        BigDecimal usdt = BigDecimal.ZERO;
        BigDecimal reais = BigDecimal.ZERO;
        if (a.dados() != null) reais = reais.add(a.dados().saldo());
        if (b.dados() != null) {
            reais = reais.add(b.dados().brl());
            usdt = usdt.add(b.dados().usdt());
        }
        if (p.dados() != null) usdt = usdt.add(p.dados().usdt());
        if (d.dados() != null) usdt = usdt.add(d.dados().carteiraUsdt()).add(d.dados().options());
        BigDecimal total = (cotacao == null ? reais : reais.add(usdt.multiply(cotacao))).setScale(2, RoundingMode.HALF_UP);
        Financeiro f = new Financeiro(agora, periodo, cotacao, total, total.subtract(jogo.moedasPerfis()), a, b, p, d,
                jogo);
        guardado = f;
        return f;
    }

    private Asaas asaas(Instant desde) {
        BigDecimal saldo = asaas.saldo();
        BigDecimal entradas = BigDecimal.ZERO, saidas = BigDecimal.ZERO, taxas = BigDecimal.ZERO;
        List<Movimento> movimentos = new ArrayList<>();
        for (JsonNode m : asaas.extrato(desde.atZone(FUSO).toLocalDate(), 100)) {
            BigDecimal valor = m.path("value").decimalValue();
            String tipo = m.path("type").asText("");
            if (tipo.contains("FEE")) {
                taxas = taxas.add(valor.abs());
            } else if (valor.signum() > 0) {
                entradas = entradas.add(valor);
            } else {
                saidas = saidas.add(valor.abs());
            }
            movimentos.add(new Movimento(m.path("date").asText(null), descricaoAsaas(m), valor, "BRL", tipoAsaas(tipo)));
        }
        movimentos.sort(Comparator.comparing((Movimento x) -> x.quando() == null ? "" : x.quando()).reversed());
        return new Asaas(saldo, repasse.caixaLivreDe(saldo), entradas, saidas, taxas,
                movimentos.stream().limit(ULTIMOS).toList());
    }

    private static String descricaoAsaas(JsonNode m) {
        String d = m.path("description").asText("");
        return d.isBlank() ? tipoAsaas(m.path("type").asText("")) : d;
    }

    static String tipoAsaas(String tipo) {
        return switch (tipo) {
            case "PAYMENT_RECEIVED" -> "Pix recebido";
            case "PAYMENT_FEE", "PIX_TRANSACTION_FEE" -> "Taxa do Asaas";
            case "TRANSFER", "PIX_TRANSACTION_DEBIT" -> "Pix enviado";
            case "TRANSFER_FEE" -> "Taxa de transferência";
            case "TRANSFER_REVERSAL", "PIX_TRANSACTION_CREDIT_REFUND", "PIX_TRANSACTION_DEBIT_REFUND" -> "Estorno";
            case "PIX_TRANSACTION_CREDIT" -> "Pix recebido";
            default -> tipo.isBlank() ? "Movimento" : tipo;
        };
    }

    private Binance binance(Instant desde) {
        Map<String, BigDecimal> saldos = binance.saldos();
        long desdeMs = desde.toEpochMilli();
        List<Movimento> movimentos = new ArrayList<>();
        BigDecimal depositos = BigDecimal.ZERO, saquesBrl = BigDecimal.ZERO;
        for (int tipo = 0; tipo <= 1; tipo++) {
            for (JsonNode o : binance.ordensFiat(tipo, desdeMs)) {
                BigDecimal valor = new BigDecimal(o.path("amount").asText(o.path("indicatedAmount").asText("0")));
                String situacao = o.path("status").asText("");
                if ("Successful".equalsIgnoreCase(situacao)) {
                    if (tipo == 0) depositos = depositos.add(valor);
                    else saquesBrl = saquesBrl.add(valor);
                }
                movimentos.add(new Movimento(quando(o.path("createTime").asLong()),
                        tipo == 0 ? "Pix recebido (depósito)" : "Pix enviado (saque)",
                        tipo == 0 ? valor : valor.negate(), "BRL", situacaoFiat(situacao)));
            }
        }
        BigDecimal comprado = BigDecimal.ZERO, gasto = BigDecimal.ZERO;
        for (JsonNode n : binance.negocios("USDTBRL")) {
            long em = n.path("time").asLong();
            if (em < desdeMs) continue;
            BigDecimal qtd = new BigDecimal(n.path("qty").asText("0"));
            BigDecimal reais = new BigDecimal(n.path("quoteQty").asText("0"));
            boolean compra = n.path("isBuyer").asBoolean();
            if (compra) {
                comprado = comprado.add(qtd);
                gasto = gasto.add(reais);
            }
            movimentos.add(new Movimento(quando(em), (compra ? "Compra" : "Venda") + " de USDT por R$ "
                    + reais.setScale(2, RoundingMode.HALF_UP).toPlainString(), compra ? qtd : qtd.negate(), "USDT",
                    "concluído"));
        }
        BigDecimal sacado = BigDecimal.ZERO;
        for (JsonNode s : binance.saquesCripto(desdeMs)) {
            BigDecimal qtd = new BigDecimal(s.path("amount").asText("0"));
            int status = s.path("status").asInt(-1);
            if (status == 6) sacado = sacado.add(qtd);
            movimentos.add(new Movimento(quandoUtc(s.path("applyTime").asText(null)),
                    "Saque de " + s.path("coin").asText("") + " (" + s.path("network").asText("") + ")", qtd.negate(),
                    s.path("coin").asText(""), situacaoSaque(status)));
        }
        movimentos.sort(Comparator.comparing((Movimento x) -> x.quando() == null ? "" : x.quando()).reversed());
        return new Binance(saldos.getOrDefault("BRL", BigDecimal.ZERO), saldos.getOrDefault("USDT", BigDecimal.ZERO),
                saldos.getOrDefault("BNB", BigDecimal.ZERO), depositos, saquesBrl, comprado,
                gasto.setScale(2, RoundingMode.HALF_UP), sacado, movimentos.stream().limit(ULTIMOS).toList());
    }

    /** "2026-11-16 03:00:00" (UTC, como a Binance manda os saques) no horario de Sao Paulo. */
    static String quandoUtc(String texto) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(texto.replace(' ', 'T')).atZone(java.time.ZoneOffset.UTC)
                    .withZoneSameInstant(FUSO).toLocalDateTime().toString();
        } catch (java.time.format.DateTimeParseException e) {
            return texto;
        }
    }

    private String quando(long epochMs) {
        return epochMs <= 0 ? null : Instant.ofEpochMilli(epochMs).atZone(FUSO).toLocalDateTime().toString();
    }

    static String situacaoFiat(String s) {
        return switch (s.toLowerCase()) {
            case "successful" -> "concluído";
            case "processing" -> "em andamento";
            case "failed" -> "falhou";
            case "refunded" -> "estornado";
            default -> s;
        };
    }

    static String situacaoSaque(int status) {
        return switch (status) {
            case 6 -> "concluído";
            case 4 -> "em andamento";
            case 2 -> "aguardando aprovação";
            case 1 -> "cancelado";
            case 3 -> "recusado";
            case 5 -> "falhou";
            default -> "pendente";
        };
    }

    private Ponte ponte() {
        return new Ponte(carteira.endereco(), new BigDecimal(carteira.saldoUsdtWei()).divide(new BigDecimal(WEI)),
                new BigDecimal(carteira.saldoBnbWei()).divide(new BigDecimal(WEI)), carteira.gasSuficiente());
    }

    private Deriv deriv() {
        DerivCaixa.Carteira c = deriv.carteiraPrincipal();
        DerivCaixa.SaldoOptions o = deriv.saldoOptions();
        return new Deriv(c.saldo(), c.saldos(), o.saldo(), o.moeda());
    }

    private Jogo jogo() {
        List<Carteira> todas = carteiras.findAll();
        BigDecimal perfis = BigDecimal.ZERO, poupanca = BigDecimal.ZERO;
        int quantos = 0;
        for (Carteira c : todas) {
            if (Poupanca.CARTEIRA.equals(c.getUsuarioId())) {
                poupanca = c.getSaldo();
            } else {
                perfis = perfis.add(c.getSaldo());
                quantos++;
            }
        }
        RepasseService.Situacao s = tentar("repasse", repasse::situacao).dados();
        return new Jogo(perfis, quantos, poupanca, s == null ? 0 : s.naFila(),
                s == null ? BigDecimal.ZERO : s.valorNaFila(), s == null ? "indisponível" : s.motivo());
    }

    private static <T> Parte<T> naoConfigurado() {
        return Parte.falhou("não configurado neste ambiente");
    }

    private static <T> Parte<T> tentar(String onde, Supplier<T> consulta) {
        try {
            return Parte.ok(consulta.get());
        } catch (RuntimeException e) {
            log.warn("Painel financeiro: {} nao respondeu ({})", onde, e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Parte.falhou(msg.length() > 300 ? msg.substring(0, 300) : msg);
        }
    }
}
