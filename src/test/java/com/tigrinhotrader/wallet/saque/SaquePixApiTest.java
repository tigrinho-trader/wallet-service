package com.tigrinhotrader.wallet.saque;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tigrinhotrader.wallet.repasse.AsaasCaixa;
import com.tigrinhotrader.wallet.repasse.AsaasCaixa.Transferencia;
import com.tigrinhotrader.wallet.repasse.RepasseException;
import com.tigrinhotrader.wallet.servico.CarteiraService;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Saque Pix pela API, com a carteira e o banco de verdade (so o Asaas e o user-service sao falsos): so conta ADMIN
 * ve e usa, e nunca se saca mais moedas do que a carteira tem, mesmo com dinheiro sobrando no Asaas.
 */
@SpringBootTest(properties = "wallet.saldo-inicial=0") // sem bonus de boas-vindas: cada teste da as moedas que quer
@AutoConfigureMockMvc
class SaquePixApiTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CarteiraService carteiras;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private AsaasCaixa asaas;
    @MockBean
    private ClienteUsuarios usuarios;

    private String usuario;

    @BeforeEach
    void preparar() {
        usuario = "auth0|api-" + CONTADOR.incrementAndGet();
        when(usuarios.admin(usuario)).thenReturn(true);   // o dono e ADMIN; qualquer outro id nao
        when(asaas.configurado()).thenReturn(true);
        when(asaas.saldo()).thenReturn(new BigDecimal("300.00"));
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new Transferencia("tr_api", "PENDING", true, BigDecimal.ZERO, "OSCAR R DE S FILHO", null));
    }

    private void moedas(String quantas) {
        carteiras.creditarCompra(usuario, new BigDecimal(quantas), "teste-" + usuario, "moedas de teste");
    }

    private ResultActions sacar(String corpo) throws Exception {
        return mockMvc.perform(post("/carteira/saques-pix").header("X-Usuario-Id", usuario)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private ResultActions carteira() throws Exception {
        return mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario));
    }

    @Test
    void quemNaoEAdminNaoVeOSaque() throws Exception {
        moedas("100");
        when(usuarios.admin(usuario)).thenReturn(false);
        mockMvc.perform(get("/carteira/saques-pix").header("X-Usuario-Id", usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permitido").value(false))
                .andExpect(jsonPath("$.disponivel").doesNotExist());

        mockMvc.perform(post("/carteira/saques-pix").header("X-Usuario-Id", usuario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoChave\":\"CPF\",\"chave\":\"52998224725\",\"valor\":10}"))
                .andExpect(status().isNotFound());
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
        carteira().andExpect(jsonPath("$.saldo").value(100.0));
    }

    @Test
    void com155MoedasNaoSaca20MesmoComCaixaNoAsaas() throws Exception {
        moedas("15.50");

        mockMvc.perform(get("/carteira/saques-pix").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.permitido").value(true))
                .andExpect(jsonPath("$.moedas").value(15.5))
                .andExpect(jsonPath("$.caixa").value(300.0))
                .andExpect(jsonPath("$.disponivel").value(15.5));

        sacar("{\"tipoChave\":\"CPF\",\"chave\":\"529.982.247-25\",\"valor\":20}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente na plataforma: voce tem 15.50 moedas (R$ 15.50)"));

        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
        carteira().andExpect(jsonPath("$.saldo").value(15.5));   // nada debitado
    }

    @Test
    void sacarDebitaAsMoedasNoExtrato() throws Exception {
        moedas("15.50");

        sacar("{\"tipoChave\":\"CPF\",\"chave\":\"529.982.247-25\",\"valor\":15.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENVIADO"))
                .andExpect(jsonPath("$.chave").value("***.982.247-**"))
                .andExpect(jsonPath("$.destinatario").value("OSCAR R DE S FILHO"));

        carteira().andExpect(jsonPath("$.saldo").value(0.0));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$[0].tipo").value("SAQUE_PIX"))
                .andExpect(jsonPath("$[0].valor").value(-15.5));

        // sem moedas, nem um centavo a mais
        sacar("{\"tipoChave\":\"EMAIL\",\"chave\":\"a@b.com\",\"valor\":1}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pixRecusadoDevolveAsMoedas() throws Exception {
        moedas("50");
        when(asaas.transferirPix(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new RepasseException("Asaas respondeu 400: Chave Pix nao encontrada"));

        sacar("{\"tipoChave\":\"EMAIL\",\"chave\":\"a@b.com\",\"valor\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALHOU"));

        carteira().andExpect(jsonPath("$.saldo").value(50.0));
        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$[0].tipo").value("ESTORNO_SAQUE_PIX"))
                .andExpect(jsonPath("$[1].tipo").value("SAQUE_PIX"));
    }

    @Test
    void chaveInvalidaOuAcimaDoCaixaDa422() throws Exception {
        moedas("1000");

        sacar("{\"tipoChave\":\"CPF\",\"chave\":\"123\",\"valor\":10}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("CPF invalido"));

        sacar("{\"tipoChave\":\"EMAIL\",\"chave\":\"a@b.com\",\"valor\":400}") // caixa do Asaas: R$ 300
                .andExpect(status().isUnprocessableEntity());
        verify(asaas, never()).transferirPix(anyString(), anyString(), any(), anyString(), anyString());
        carteira().andExpect(jsonPath("$.saldo").value(1000.0));
    }
}
