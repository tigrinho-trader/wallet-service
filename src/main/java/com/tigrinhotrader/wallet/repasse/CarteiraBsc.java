package com.tigrinhotrader.wallet.repasse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * Carteira ponte na BNB Smart Chain: recebe o USDT da Binance e repassa na hora para a Deriv.
 * Fala com a rede por JSON-RPC e assina as transacoes localmente (a chave nunca sai do servico).
 */
public class CarteiraBsc {

    static final String USDT = "0x55d398326f99059fF775485246999027B3197955"; // USDT (BEP20), 18 casas
    static final long CHAIN_ID = 56;
    static final BigInteger GAS_TETO = BigInteger.valueOf(100_000);

    private final RestClient http;
    private final Credentials credenciais;

    public CarteiraBsc(RestClient.Builder builder, String rpc, String chavePrivada) {
        this.http = builder.baseUrl(rpc).build();
        this.credenciais = chavePrivada == null || chavePrivada.isBlank() ? null : Credentials.create(chavePrivada.trim());
    }

    public boolean configurada() {
        return credenciais != null;
    }

    public String endereco() {
        return credenciais.getAddress();
    }

    public BigInteger saldoBnbWei() {
        return Numeric.toBigInt(rpc("eth_getBalance", endereco(), "latest").asText());
    }

    public BigInteger saldoUsdtWei() {
        String dados = "0x70a08231" + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(endereco()), 64);
        return Numeric.toBigInt(rpc("eth_call", Map.of("to", USDT, "data", dados), "latest").asText());
    }

    public BigInteger precoGas() {
        return Numeric.toBigInt(rpc("eth_gasPrice").asText());
    }

    /** BNB suficiente para pelo menos 3 envios no teto de gas. */
    public boolean gasSuficiente() {
        return saldoBnbWei().compareTo(precoGas().multiply(GAS_TETO).multiply(BigInteger.valueOf(3))) >= 0;
    }

    /** Assina e envia transfer(destino, wei) do USDT. Devolve o hash da transacao. */
    public String enviarUsdt(String destino, BigInteger wei) {
        String dados = dadosTransfer(destino, wei);
        BigInteger estimado = Numeric.toBigInt(rpc("eth_estimateGas",
                Map.of("from", endereco(), "to", USDT, "data", dados)).asText());
        BigInteger gas = estimado.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN).min(GAS_TETO);
        BigInteger nonce = Numeric.toBigInt(rpc("eth_getTransactionCount", endereco(), "pending").asText());
        RawTransaction tx = RawTransaction.createTransaction(nonce, precoGas(), gas, USDT, BigInteger.ZERO, dados);
        String assinada = Numeric.toHexString(TransactionEncoder.signMessage(tx, CHAIN_ID, credenciais));
        return rpc("eth_sendRawTransaction", assinada).asText();
    }

    /** vazio = ainda pendente; true = confirmada com sucesso; false = falhou na rede. */
    public Optional<Boolean> confirmada(String hash) {
        JsonNode recibo = rpc("eth_getTransactionReceipt", hash);
        if (recibo == null || recibo.isNull() || recibo.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(BigInteger.ONE.equals(Numeric.toBigInt(recibo.path("status").asText("0x0"))));
    }

    static String dadosTransfer(String destino, BigInteger wei) {
        return "0xa9059cbb" + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(destino), 64)
                + Numeric.toHexStringNoPrefixZeroPadded(wei, 64);
    }

    private JsonNode rpc(String metodo, Object... parametros) {
        JsonNode resposta = http.post().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", metodo, "params", List.of(parametros)))
                .retrieve().body(JsonNode.class);
        if (resposta == null) {
            throw new IllegalStateException("BSC respondeu sem corpo");
        }
        if (resposta.has("error")) {
            // a rede respondeu recusando: e certo que a operacao nao aconteceu
            throw new RepasseException("BSC: " + resposta.path("error").path("message").asText(resposta.path("error").toString()));
        }
        return resposta.path("result");
    }
}
