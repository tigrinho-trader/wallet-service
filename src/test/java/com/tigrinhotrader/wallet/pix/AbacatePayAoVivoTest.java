package com.tigrinhotrader.wallet.pix;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.wallet.pix.ProvedorPix.CobrancaPix;
import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Conversa de verdade com o AbacatePay em modo de desenvolvimento (nenhum dinheiro real).
 * So roda com uma chave de teste no ambiente: {@code ABACATEPAY_API_KEY=abc_dev_...}. No CI fica pulado.
 */
@EnabledIfEnvironmentVariable(named = "ABACATEPAY_API_KEY", matches = "abc_dev_.+")
class AbacatePayAoVivoTest {

    private final AbacatePayProvedorPix provedor = new AbacatePayProvedorPix(RestClient.builder(),
            "https://api.abacatepay.com/v2", System.getenv("ABACATEPAY_API_KEY"));

    @Test
    void criaConsultaESimulaOPagamento() {
        CobrancaPix cobranca = provedor.criar(UUID.randomUUID().toString(), 100, "Flecha Certeira: teste", 600);

        assertThat(cobranca.id()).isNotBlank();
        assertThat(cobranca.status()).isEqualTo(StatusCobranca.PENDENTE);
        assertThat(cobranca.devMode()).isTrue();
        assertThat(cobranca.brCode()).isNotBlank();
        assertThat(cobranca.brCodeBase64()).startsWith("data:image/png;base64,");
        assertThat(cobranca.expiraEm()).isNotNull();
        assertThat(provedor.consultar(cobranca.id())).isEqualTo(StatusCobranca.PENDENTE);

        provedor.simularPagamento(cobranca.id());

        assertThat(provedor.consultar(cobranca.id())).isEqualTo(StatusCobranca.PAGA);
    }
}
