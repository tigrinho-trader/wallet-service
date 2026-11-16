package com.tigrinhotrader.wallet.painel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tigrinhotrader.wallet.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Carteira de poupanca do super-admin: nasce sem bonus, recebe a parte poupada e so o painel ve. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:painelcarteira;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PainelCarteiraApiTest {

    private static final String ADMIN = PainelCarteiraController.CABECALHO;

    @Autowired
    MockMvc mvc;
    @Autowired
    CarteiraService carteiras;
    @Autowired
    com.tigrinhotrader.wallet.dominio.CarteiraRepository repositorio;
    @MockBean
    RabbitTemplate rabbitTemplate;

    private static OrdemExecutadaEvento poupanca(UUID referencia, String valor) {
        return new OrdemExecutadaEvento(referencia, Poupanca.CARTEIRA, "1HZ100V", Poupanca.TIPO_EVENTO,
                new BigDecimal(valor), "GANHOU", new BigDecimal(valor), Instant.parse("2026-11-16T01:00:00Z"));
    }

    @Test
    void semOCabecalhoDoGatewayRecusa() throws Exception {
        mvc.perform(get("/painel/poupanca")).andExpect(status().isForbidden());
        mvc.perform(get("/painel/carteiras")).andExpect(status().isForbidden());
        // o jogador nao chega na carteira de poupanca pelo id dele (que vem do token)
        mvc.perform(get("/painel/poupanca").header("X-Usuario-Id", Poupanca.CARTEIRA)).andExpect(status().isForbidden());
    }

    @Test
    void parteGuardadaEntraNaPoupancaSemBonusEUmaVezSo() throws Exception {
        double antes = repositorio.findById(Poupanca.CARTEIRA).map(c -> c.getSaldo().doubleValue()).orElse(0.0);
        UUID referencia = UUID.randomUUID();
        assertThat(carteiras.aplicarResultadoRodada(poupanca(referencia, "0.95"))).isTrue();
        assertThat(carteiras.aplicarResultadoRodada(poupanca(referencia, "0.95"))).isFalse();   // repetido
        carteiras.aplicarResultadoRodada(poupanca(UUID.randomUUID(), "1.10"));

        // nos testes a carteira de jogador nasce com 1000; a de poupanca, com 0 (e nao ganha o bonus)
        assertThat(antes).isLessThan(1000);
        mvc.perform(get("/painel/poupanca").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo", closeTo(antes + 2.05, 0.001)))
                .andExpect(jsonPath("$.movimentos[0].valor").value(1.10))
                .andExpect(jsonPath("$.movimentos[0].descricao").value("Poupança de uma flecha vencedora (1HZ100V)"))
                .andExpect(jsonPath("$.movimentos[1].valor").value(0.95));
    }

    @Test
    void painelFinanceiroSoParaOPainelECadaParteDizSeEstaConfigurada() throws Exception {
        mvc.perform(get("/painel/financeiro")).andExpect(status().isForbidden());
        mvc.perform(get("/painel/financeiro").param("dias", "30").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias").value(30))
                .andExpect(jsonPath("$.asaas.erro").value("não configurado neste ambiente"))
                .andExpect(jsonPath("$.jogo.perfis").exists());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putConfig(String corpo) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/painel/config")
                .header(ADMIN, "super-admin-oscar").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(corpo);
    }

    @Test
    void minimoDoPixPeloPainelComPadrao30() throws Exception {
        mvc.perform(get("/painel/config")).andExpect(status().isForbidden());
        mvc.perform(get("/painel/config").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atual.pixMinimoMoedas").value(30))
                .andExpect(jsonPath("$.ambiente.pixMinimoMoedas").value(30));
        mvc.perform(putConfig("{\"pixMinimoMoedas\":50}")).andExpect(jsonPath("$.atual.pixMinimoMoedas").value(50));
        mvc.perform(putConfig("{\"pixMinimoMoedas\":0}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(putConfig("{}")).andExpect(jsonPath("$.atual.pixMinimoMoedas").value(30));   // vazio volta ao padrao
    }

    @Test
    void situacaoDoRepasseSoParaOPainel() throws Exception {
        mvc.perform(get("/painel/repasse")).andExpect(status().isForbidden());
        // nos testes o repasse fica desligado (sem as chaves do Asaas, da Binance e da carteira ponte)
        mvc.perform(get("/painel/repasse").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.startsWith("Repasse desligado")));
    }

    @Test
    void listaAsCarteirasDosPerfisSemADePoupanca() throws Exception {
        carteiras.obterOuCriar("perfil-painel-1");
        carteiras.aplicarResultadoRodada(poupanca(UUID.randomUUID(), "0.50"));

        mvc.perform(get("/painel/carteiras").header(ADMIN, "super-admin-oscar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].usuarioId", hasItem("perfil-painel-1")))
                .andExpect(jsonPath("$[*].usuarioId", not(hasItem(Poupanca.CARTEIRA))));
    }
}
