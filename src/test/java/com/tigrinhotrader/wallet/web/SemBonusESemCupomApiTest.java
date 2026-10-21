package com.tigrinhotrader.wallet.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Padroes de producao: carteira nova com 0 moedas e nenhum cupom configurado. */
@SpringBootTest(properties = {"wallet.saldo-inicial=0", "wallet.cupom.codigo=", "wallet.cupom.moedas=0"})
@AutoConfigureMockMvc
class SemBonusESemCupomApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Test
    void carteiraNovaComecaZeradaESemTransacao() throws Exception {
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", "sem-bonus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(0.0));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", "sem-bonus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void semCupomConfiguradoQualquerCodigoE422() throws Exception {
        for (String codigo : new String[] {"30COINS", ""}) {
            mockMvc.perform(post("/carteira/cupons").header("X-Usuario-Id", "sem-cupom")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"" + codigo + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value("Cupom invalido"));
        }
    }
}
