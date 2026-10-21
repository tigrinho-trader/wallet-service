package com.tigrinhotrader.wallet.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tigrinhotrader.wallet.dominio.TransacaoRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Resgate de cupom (config dos testes: 30COINS = 30 moedas, carteira nova com 1000). */
@SpringBootTest
@AutoConfigureMockMvc
class CupomApiTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @SpyBean
    private TransacaoRepository transacoes;

    private String usuario;

    @BeforeEach
    void preparar() {
        usuario = "cupom-" + CONTADOR.incrementAndGet();
    }

    private ResultActions resgatar(String quem, String corpo) throws Exception {
        return mockMvc.perform(post("/carteira/cupons").header("X-Usuario-Id", quem)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test
    void cupomCertoCreditaEApareceNoExtrato() throws Exception {
        resgatar(usuario, "{\"codigo\":\"30COINS\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moedas").value(30.00))
                .andExpect(jsonPath("$.saldo").value(1030.00));

        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(1030.00));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.tipo=='CUPOM')].valor").value(30.00))
                .andExpect(jsonPath("$[?(@.tipo=='CUPOM')].saldoApos").value(1030.00))
                .andExpect(jsonPath("$[?(@.tipo=='CUPOM')].descricao").value("Cupom 30COINS"))
                .andExpect(jsonPath("$[?(@.tipo=='CUPOM')].referencia").value("cupom:30COINS:" + usuario));
    }

    @Test
    void segundaVezNaMesmaContaE409() throws Exception {
        resgatar(usuario, "{\"codigo\":\"30COINS\"}").andExpect(status().isOk());

        resgatar(usuario, "{\"codigo\":\"30coins\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Cupom ja usado nesta conta"));
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(1030.00));
        // outra conta ainda pode usar
        resgatar(usuario + "-b", "{\"codigo\":\"30COINS\"}").andExpect(status().isOk());
    }

    @Test
    void comparaSemDiferenciarMaiusculasEIgnoraEspacos() throws Exception {
        resgatar(usuario, "{\"codigo\":\"  30Coins \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(1030.00));
    }

    @Test
    void codigoErradoOuVazioE422() throws Exception {
        for (String corpo : new String[] {"{\"codigo\":\"31COINS\"}", "{\"codigo\":\"\"}", "{\"codigo\":\"  \"}", "{}"}) {
            resgatar(usuario, corpo)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.detail").value("Cupom invalido"));
        }
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(1000.00));
    }

    @Test
    void semUsuarioE401ESemCorpoE400() throws Exception {
        mockMvc.perform(post("/carteira/cupons").contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"30COINS\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/carteira/cupons").header("X-Usuario-Id", usuario).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corridaPegaNaReferenciaUnicaECreditaUmaVezSo() throws Exception {
        resgatar(usuario, "{\"codigo\":\"30COINS\"}").andExpect(status().isOk());
        // Simula o segundo pedido que conferiu antes do primeiro gravar: a checagem diz "nao usado",
        // o insert bate na referencia unica e a nova consulta mostra que o cupom ja foi usado.
        // (o @SpyBean volta ao normal depois de cada teste)
        String referencia = "cupom:30COINS:" + usuario;
        doReturn(false).doReturn(true).when(transacoes).existsByReferencia(referencia);

        resgatar(usuario, "{\"codigo\":\"30COINS\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cupom ja usado nesta conta"));

        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(1030.00));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$[?(@.tipo=='CUPOM')]", hasSize(1)));
    }
}
