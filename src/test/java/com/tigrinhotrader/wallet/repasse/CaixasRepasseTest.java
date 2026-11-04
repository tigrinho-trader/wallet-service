package com.tigrinhotrader.wallet.repasse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;

class CaixasRepasseTest {

    @Test
    void saqueDaBinanceVaiTodoNaUrlComOQuestionarioDeAutocustodia() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        BinanceCaixa binance = new BinanceCaixa(builder, "https://api.binance.test", "k", "s");
        servidor.expect(requestTo(allOf(startsWith("https://api.binance.test/sapi/v1/localentity/withdraw/apply?coin=USDT"),
                        containsString("questionnaire=%7B%22isAddressOwner%22%3A1%2C%22sendTo%22%3A1%2C%22declaration%22%3Atrue%7D"),
                        containsString("&signature="))))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(""))
                .andRespond(withSuccess("{\"trId\":77,\"accepted\":true,\"info\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(binance.sacarAutocustodia("USDT", new BigDecimal("10.40"), "0xabc")).isEqualTo("77");
        servidor.verify();
    }

    @Test
    void depositoPixDaBinanceVaiEmJsonEOCodigoVemDoDetalhe() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        BinanceCaixa binance = new BinanceCaixa(builder, "https://api.binance.test", "k", "s");
        servidor.expect(requestTo(allOf(startsWith("https://api.binance.test/sapi/v1/fiat/deposit?timestamp="),
                        not(containsString("currency")))))
                .andExpect(content().json("{\"currency\":\"BRL\",\"apiPaymentMethod\":\"pix\",\"amount\":\"57.21\"}", true))
                .andRespond(withSuccess("{\"code\":\"000000\",\"data\":{\"orderId\":\"ORD1\"}}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(startsWith("https://api.binance.test/sapi/v1/fiat/get-order-detail?orderNo=ORD1")))
                .andRespond(withSuccess("{\"code\":\"000000\",\"data\":{\"orderId\":\"ORD1\",\"ext\":{\"qrCode\":"
                        + "\"00020101021226990014br.gov.bcb.pix2577pix.x/qr540553.425802BR6304ABCD\"}}}", MediaType.APPLICATION_JSON));

        assertThat(binance.pedirDepositoPix(new BigDecimal("57.21"))).isEqualTo("ORD1");
        assertThat(binance.codigoPix("ORD1")).startsWith("000201").contains("br.gov.bcb.pix");
        servidor.verify();
    }

    @Test
    void carteiraAssinaATransferenciaDeUsdtComAChaveCerta() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        // chave privada 1: endereco conhecido
        CarteiraBsc carteira = new CarteiraBsc(builder, "https://rpc.test",
                "0x0000000000000000000000000000000000000000000000000000000000000001");
        String eu = "0x7e5f4552091a69125d5dfcb7b8c2659029395bdf";
        assertThat(carteira.endereco()).isEqualToIgnoringCase(eu);

        String[] assinada = new String[1];
        servidor.expect(jsonPath("$.method").value("eth_estimateGas"))
                .andRespond(withSuccess("{\"result\":\"0xb6d3\"}", MediaType.APPLICATION_JSON));
        servidor.expect(jsonPath("$.method").value("eth_getTransactionCount"))
                .andRespond(withSuccess("{\"result\":\"0x5\"}", MediaType.APPLICATION_JSON));
        servidor.expect(jsonPath("$.method").value("eth_gasPrice"))
                .andRespond(withSuccess("{\"result\":\"0xbebc200\"}", MediaType.APPLICATION_JSON));
        servidor.expect(jsonPath("$.method").value("eth_sendRawTransaction"))
                .andExpect(req -> assinada[0] = ((org.springframework.mock.http.client.MockClientHttpRequest) req)
                        .getBodyAsString().replaceAll(".*\"params\":\\[\"(0x[0-9a-f]+)\"\\].*", "$1"))
                .andRespond(withSuccess("{\"result\":\"0xHASH\"}", MediaType.APPLICATION_JSON));

        String deriv = "0xf17991177DA858B3Da4B86c8079ab3a06018deAE";
        BigInteger wei = new BigDecimal("10.39").movePointRight(18).toBigInteger();
        assertThat(carteira.enviarUsdt(deriv, wei)).isEqualTo("0xHASH");
        servidor.verify();

        SignedRawTransaction tx = (SignedRawTransaction) TransactionDecoder.decode(assinada[0]);
        assertThat(tx.getFrom()).isEqualToIgnoringCase(eu);
        assertThat(tx.getChainId()).isEqualTo(56L);
        assertThat(tx.getTo()).isEqualToIgnoringCase(CarteiraBsc.USDT);
        assertThat(tx.getNonce()).isEqualTo(BigInteger.valueOf(5));
        assertThat(tx.getData()).isEqualTo(CarteiraBsc.dadosTransfer(deriv, wei).substring(2));
    }

    @Test
    void transferenciaDaDerivUsaCotacaoEIdIdempotente() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        DerivCaixa deriv = new DerivCaixa(builder, "https://deriv.test", "tok", "app");
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        servidor.expect(requestTo("https://deriv.test/wallet/v1/wallets"))
                .andRespond(withSuccess("{\"data\":{\"wallets\":[{\"type\":\"main\",\"wallet_id\":\"W1\","
                        + "\"balances\":{\"USDT_BSC\":{\"balance\":\"31.37\"}}}]}}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://deriv.test/trading/v1/options/accounts"))
                .andRespond(withSuccess("{\"data\":[{\"account_id\":\"DOT1\",\"account_type\":\"demo\",\"status\":\"active\",\"currency\":\"USD\"},"
                        + "{\"account_id\":\"ROT1\",\"account_type\":\"real\",\"status\":\"active\",\"currency\":\"USD\"}]}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://deriv.test/wallet/v1/exchange-rate?source_currency=USDT_BSC&destination_currency=USD"))
                .andRespond(withSuccess("{\"data\":{\"exchange_rate\":\"0.99819\",\"rate_token\":\"RT\"}}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("https://deriv.test/wallet/v1/transfers/platforms"))
                .andExpect(content().json("{\"wallet_id\":\"W1\",\"platform_account_id\":\"ROT1\",\"amount\":\"31.31\","
                        + "\"currency\":\"USD\",\"direction\":\"from_wallet\",\"platform_name\":\"options\","
                        + "\"request_id\":\"00000000-0000-0000-0000-000000000042\",\"wallet_currency\":\"USDT_BSC\","
                        + "\"exchange_rate\":\"0.99819\",\"rate_token\":\"RT\"}"))
                .andRespond(withSuccess("{\"data\":{\"status\":\"TransferSuccessful\"}}", MediaType.APPLICATION_JSON));

        assertThat(deriv.transferirParaOptions(id)).isEqualByComparingTo("31.31");
        servidor.verify();
    }
}
