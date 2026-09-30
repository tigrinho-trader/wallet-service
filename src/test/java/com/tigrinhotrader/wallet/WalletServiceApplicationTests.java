package com.tigrinhotrader.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tigrinhotrader.wallet.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.wallet.mensageria.OrdemExecutadaListener;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Sobe o servico inteiro com H2 e exercita API REST + consumo do evento ordem.executada. */
@SpringBootTest
@AutoConfigureMockMvc
class WalletServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrdemExecutadaListener listener;
    @Autowired
    private CarteiraService carteiraService;
    @Autowired
    @Qualifier("filaOrdens")
    private Queue filaOrdens;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    private static OrdemExecutadaEvento evento(UUID ordemId, String usuario, String liquido, String resultado) {
        return new OrdemExecutadaEvento(ordemId, usuario, "BTCUSDT", "ALTA", new BigDecimal("100"), resultado,
                new BigDecimal(liquido), Instant.now());
    }

    @Test
    void primeiroAcessoCriaCarteiraComBonus() throws Exception {
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", "novato"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value("novato"))
                .andExpect(jsonPath("$.saldo").value(1000.00));
        // segundo acesso nao da bonus de novo
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", "novato"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tipo").value("BONUS_INICIAL"));
    }

    @Test
    void depositoESaqueSandbox() throws Exception {
        mockMvc.perform(post("/carteira/depositos").header("X-Usuario-Id", "d1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":250.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(1250.50));
        mockMvc.perform(post("/carteira/saques").header("X-Usuario-Id", "d1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":50.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(1200.00));

        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", "d1"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.tipo=='SAQUE')].valor").value(-50.50))
                .andExpect(jsonPath("$[?(@.tipo=='DEPOSITO')].referencia").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.startsWith("sbx_"))));
    }

    @Test
    void recusasDevolvem422ESaldoNaoMuda() throws Exception {
        // acima do limite do sandbox
        mockMvc.perform(post("/carteira/depositos").header("X-Usuario-Id", "r1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":5000.01}"))
                .andExpect(status().isUnprocessableEntity());
        // saque maior que o saldo
        mockMvc.perform(post("/carteira/saques").header("X-Usuario-Id", "r1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":1000.01}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: saldo atual 1000.00"));
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", "r1"))
                .andExpect(jsonPath("$.saldo").value(1000.00));
    }

    @Test
    void saqueRecusadoPeloSandboxDesfazDebito() throws Exception {
        carteiraService.depositar("r2", new BigDecimal("5000"));
        carteiraService.depositar("r2", new BigDecimal("5000"));
        mockMvc.perform(post("/carteira/saques").header("X-Usuario-Id", "r2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":6000}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", "r2"))
                .andExpect(jsonPath("$.saldo").value(11000.00));
    }

    @Test
    void validacaoEAutenticacao() throws Exception {
        mockMvc.perform(get("/carteira")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/carteira/depositos").header("X-Usuario-Id", "v1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/carteira/depositos").header("X-Usuario-Id", "v1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":1.001}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resultadoDeRodadaAtualizaSaldoUmaVezSo() throws Exception {
        UUID ganho = UUID.randomUUID();
        listener.aoReceber(evento(ganho, "t1", "90.00", "GANHOU"));
        listener.aoReceber(evento(ganho, "t1", "90.00", "GANHOU")); // entrega duplicada
        listener.aoReceber(evento(UUID.randomUUID(), "t1", "-100.00", "PERDEU"));
        listener.aoReceber(evento(UUID.randomUUID(), "t1", "0", "EMPATOU"));

        mockMvc.perform(get("/carteira").header("X-Usuario-Id", "t1"))
                .andExpect(jsonPath("$.saldo").value(990.00));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", "t1"))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.tipo=='GANHO_RODADA')].referencia").value(ganho.toString()))
                .andExpect(jsonPath("$[?(@.tipo=='PERDA_RODADA')].valor").value(-100.00))
                .andExpect(jsonPath("$[?(@.tipo=='EMPATE_RODADA')].descricao").value("Rodada ALTA BTCUSDT: EMPATOU"));

        assertThat(carteiraService.aplicarResultadoRodada(evento(ganho, "t1", "90.00", "GANHOU"))).isFalse();
    }

    @Test
    void filaDeOrdensEDuravelComDeadLetter() {
        assertThat(filaOrdens.isDurable()).isTrue();
        assertThat(filaOrdens.getArguments())
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "wallet.ordem-executada.dlq");
    }
}
