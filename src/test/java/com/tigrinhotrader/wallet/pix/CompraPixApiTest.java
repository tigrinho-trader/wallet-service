package com.tigrinhotrader.wallet.pix;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.wallet.pix.ProvedorPix.CobrancaPix;
import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.time.Instant;
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

/** API de compra de moedas por Pix com o provedor falso (o AbacatePay de verdade fica no AbacatePayAoVivoTest). */
@SpringBootTest
@AutoConfigureMockMvc
class CompraPixApiTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private ProvedorPix provedor;

    private String usuario;

    @BeforeEach
    void preparar() {
        usuario = "jogador-" + CONTADOR.incrementAndGet();
        when(provedor.configurado()).thenReturn(true);
        when(provedor.criar(anyString(), anyLong(), anyString(), anyInt())).thenAnswer(inv -> new CobrancaPix(
                "pix_char_" + inv.getArgument(0), inv.getArgument(1), StatusCobranca.PENDENTE, "000201brcode",
                "data:image/png;base64,QR", Instant.parse("2026-10-11T13:00:00Z"), true));
    }

    private ResultActions comprar(String quem, String corpo) throws Exception {
        return mockMvc.perform(post("/carteira/compras").header("X-Usuario-Id", quem)
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private String novaCompra(int moedas) throws Exception {
        String corpo = comprar(usuario, "{\"moedas\":" + moedas + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo).get("id").asText();
    }

    private String cobrancaDe(String compraId) {
        return "pix_char_" + compraId;
    }

    @Test
    void configMostraOsPacotesEOPreco() throws Exception {
        mockMvc.perform(get("/carteira/compras/config").header("X-Usuario-Id", usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pixHabilitado").value(true))
                .andExpect(jsonPath("$.sandboxHabilitado").value(true))
                .andExpect(jsonPath("$.pacotes", hasSize(4)))
                .andExpect(jsonPath("$.pacotes[2]").value(1000))
                .andExpect(jsonPath("$.centavosPorMoeda").value(10));
    }

    @Test
    void criaACobrancaComOValorDoPacote() throws Exception {
        comprar(usuario, "{\"moedas\":500}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moedas").value(500))
                .andExpect(jsonPath("$.centavos").value(5000))
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.brCode").value("000201brcode"))
                .andExpect(jsonPath("$.brCodeBase64").value("data:image/png;base64,QR"))
                .andExpect(jsonPath("$.devMode").value(true))
                .andExpect(jsonPath("$.saldo").value(nullValue()));
        verify(provedor).criar(anyString(), eq(5000L), eq("Flecha Certeira: 500 moedas"), eq(1800));
    }

    @Test
    void pacoteForaDaListaE422() throws Exception {
        comprar(usuario, "{\"moedas\":123}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Pacote invalido")));
        verify(provedor, never()).criar(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void semMoedasE400ESemUsuarioE401() throws Exception {
        comprar(usuario, "{}").andExpect(status().isBadRequest());
        mockMvc.perform(post("/carteira/compras").contentType(MediaType.APPLICATION_JSON).content("{\"moedas\":100}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void semChaveConfiguradaE503() throws Exception {
        when(provedor.configurado()).thenReturn(false);
        comprar(usuario, "{\"moedas\":100}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ABACATEPAY_API_KEY")));
        mockMvc.perform(get("/carteira/compras/config").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.pixHabilitado").value(false));
    }

    @Test
    void provedorForaDoArE503() throws Exception {
        when(provedor.criar(anyString(), anyLong(), anyString(), anyInt()))
                .thenThrow(new PixIndisponivelException("AbacatePay indisponivel"));
        comprar(usuario, "{\"moedas\":100}").andExpect(status().isServiceUnavailable());
    }

    @Test
    void provedorSemIdDaCobrancaE503() throws Exception {
        when(provedor.criar(anyString(), anyLong(), anyString(), anyInt())).thenReturn(
                new CobrancaPix(" ", 100, StatusCobranca.PENDENTE, null, null, null, true));
        comprar(usuario, "{\"moedas\":100}").andExpect(status().isServiceUnavailable());
        when(provedor.criar(anyString(), anyLong(), anyString(), anyInt())).thenReturn(
                new CobrancaPix(null, 100, StatusCobranca.PENDENTE, null, null, null, true));
        comprar(usuario, "{\"moedas\":100}").andExpect(status().isServiceUnavailable());
    }

    @Test
    void limiteDeCobrancasEmAberto() throws Exception {
        novaCompra(100);
        novaCompra(100);
        novaCompra(100);
        comprar(usuario, "{\"moedas\":100}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("em aberto")));
    }

    @Test
    void enquantoPendenteNaoCreditaEQuandoPagaCreditaUmaVezSo() throws Exception {
        String id = novaCompra(1000);
        when(provedor.consultar(cobrancaDe(id))).thenReturn(StatusCobranca.PENDENTE);
        mockMvc.perform(get("/carteira/compras/" + id).header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.status").value("PENDENTE"));
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario)).andExpect(jsonPath("$.saldo").value(1000.0));

        when(provedor.consultar(cobrancaDe(id))).thenReturn(StatusCobranca.PAGA);
        mockMvc.perform(get("/carteira/compras/" + id).header("X-Usuario-Id", usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGA"))
                .andExpect(jsonPath("$.pagaEm").isNotEmpty())
                .andExpect(jsonPath("$.saldo").value(2000.0));
        // consultar de novo nao credita outra vez e nem pergunta ao provedor
        mockMvc.perform(get("/carteira/compras/" + id).header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(2000.0));
        verify(provedor, org.mockito.Mockito.times(2)).consultar(cobrancaDe(id));

        mockMvc.perform(get("/carteira/extrato").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$[0].tipo").value("COMPRA_PIX"))
                .andExpect(jsonPath("$[0].valor").value(1000.0))
                .andExpect(jsonPath("$[0].referencia").value(cobrancaDe(id)))
                .andExpect(jsonPath("$[0].descricao").value("Compra Pix: 1000 moedas"));
    }

    @Test
    void cobrancaExpiradaNaoCredita() throws Exception {
        String id = novaCompra(100);
        when(provedor.consultar(cobrancaDe(id))).thenReturn(StatusCobranca.EXPIRADA);
        mockMvc.perform(get("/carteira/compras/" + id).header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.status").value("EXPIRADA"))
                .andExpect(jsonPath("$.saldo").value(nullValue()));
        mockMvc.perform(get("/carteira").header("X-Usuario-Id", usuario)).andExpect(jsonPath("$.saldo").value(1000.0));
    }

    @Test
    void simulacaoEmModoDeDesenvolvimentoPagaECredita() throws Exception {
        String id = novaCompra(500);
        when(provedor.consultar(cobrancaDe(id))).thenReturn(StatusCobranca.PAGA);
        mockMvc.perform(post("/carteira/compras/" + id + "/simular-pagamento").header("X-Usuario-Id", usuario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGA"))
                .andExpect(jsonPath("$.saldo").value(1500.0));
        verify(provedor).simularPagamento(cobrancaDe(id));
        // ja paga: simular de novo nao chama o provedor
        mockMvc.perform(post("/carteira/compras/" + id + "/simular-pagamento").header("X-Usuario-Id", usuario))
                .andExpect(jsonPath("$.saldo").value(1500.0));
        verify(provedor).simularPagamento(cobrancaDe(id));
    }

    @Test
    void simulacaoForaDoModoDeDesenvolvimentoE422() throws Exception {
        when(provedor.criar(anyString(), anyLong(), anyString(), anyInt())).thenAnswer(inv -> new CobrancaPix(
                "pix_char_" + inv.getArgument(0), inv.getArgument(1), StatusCobranca.PENDENTE, "br", "img", null, false));
        String id = novaCompra(100);
        mockMvc.perform(post("/carteira/compras/" + id + "/simular-pagamento").header("X-Usuario-Id", usuario))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("desenvolvimento")));
        verify(provedor, never()).simularPagamento(anyString());
    }

    @Test
    void compraDeOutroJogadorE404() throws Exception {
        String id = novaCompra(100);
        mockMvc.perform(get("/carteira/compras/" + id).header("X-Usuario-Id", "intruso"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/carteira/compras/" + id + "/simular-pagamento").header("X-Usuario-Id", "intruso"))
                .andExpect(status().isNotFound());
    }
}
