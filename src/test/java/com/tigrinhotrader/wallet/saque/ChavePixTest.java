package com.tigrinhotrader.wallet.saque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.wallet.saque.ChavePix.Tipo;
import com.tigrinhotrader.wallet.servico.RegraNegocioException;
import org.junit.jupiter.api.Test;

class ChavePixTest {

    @Test
    void cpfECnpjVaoSoComDigitosEPrecisamSerValidos() {
        assertThat(ChavePix.de("cpf", " 529.982.247-25 ")).isEqualTo(new ChavePix(Tipo.CPF, "52998224725"));
        assertThat(ChavePix.de("CNPJ", "11.222.333/0001-81")).isEqualTo(new ChavePix(Tipo.CNPJ, "11222333000181"));
        assertThatThrownBy(() -> ChavePix.de("CPF", "529.982.247-26")).isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("CPF invalido");
        assertThatThrownBy(() -> ChavePix.de("CPF", "111.111.111-11")).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> ChavePix.de("CNPJ", "11.222.333/0001-82")).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void celularFicaComDddENoveDigitosSemMaisCinquentaECinco() {
        assertThat(ChavePix.de("PHONE", "+55 (11) 98765-4321").valor()).isEqualTo("11987654321");
        assertThat(ChavePix.de("PHONE", "11 98765-4321").valor()).isEqualTo("11987654321");
        assertThatThrownBy(() -> ChavePix.de("PHONE", "(11) 8765-4321")).isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("DDD");
    }

    @Test
    void emailEChaveAleatoriaFicamEmMinusculas() {
        assertThat(ChavePix.de("EMAIL", " Oscar@Exemplo.COM ").valor()).isEqualTo("oscar@exemplo.com");
        assertThat(ChavePix.de("EVP", "123E4567-E89B-12D3-A456-426614174000").valor())
                .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThatThrownBy(() -> ChavePix.de("EMAIL", "sem-arroba")).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> ChavePix.de("EVP", "1234")).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void tipoOuChaveAusenteOuDesconhecidoERecusado() {
        assertThatThrownBy(() -> ChavePix.de("BOLETO", "123")).isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("Tipo de chave");
        assertThatThrownBy(() -> ChavePix.de("CPF", " ")).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> ChavePix.de(null, "x")).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void mascaraMostraSoOSuficienteParaReconhecer() {
        assertThat(ChavePix.mascarar(Tipo.CPF, "52998224725")).isEqualTo("***.982.247-**");
        assertThat(ChavePix.mascarar(Tipo.CNPJ, "11222333000181")).isEqualTo("**.***.333/0001-**");
        assertThat(ChavePix.mascarar(Tipo.PHONE, "11987654321")).isEqualTo("(11) *****-4321");
        assertThat(ChavePix.mascarar(Tipo.EMAIL, "oscar@exemplo.com")).isEqualTo("os***@exemplo.com");
        assertThat(ChavePix.mascarar(Tipo.EVP, "123e4567-e89b-12d3-a456-426614174000")).isEqualTo("123e4567-****-4000");
    }
}
