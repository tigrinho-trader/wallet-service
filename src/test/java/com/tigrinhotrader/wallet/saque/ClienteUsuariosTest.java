package com.tigrinhotrader.wallet.saque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClienteUsuariosTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final ClienteUsuarios usuarios = new ClienteUsuarios(builder, "http://user-service:8085");

    @Test
    void perguntaAoUserServicePeloIdCodificado() {
        servidor.expect(requestTo("http://user-service:8085/interno/usuarios/google-oauth2%7C123/admin"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"admin\":true}", MediaType.APPLICATION_JSON));

        assertThat(usuarios.admin("google-oauth2|123")).isTrue();
        servidor.verify();
    }

    @Test
    void naoAdminOuQualquerErroNegaOSaque() {
        servidor.expect(requestTo("http://user-service:8085/interno/usuarios/a/admin"))
                .andRespond(withSuccess("{\"admin\":false}", MediaType.APPLICATION_JSON));
        servidor.expect(requestTo("http://user-service:8085/interno/usuarios/b/admin")).andRespond(withResourceNotFound());
        servidor.expect(requestTo("http://user-service:8085/interno/usuarios/c/admin")).andRespond(withServerError());
        servidor.expect(requestTo("http://user-service:8085/interno/usuarios/d/admin"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(usuarios.admin("a")).isFalse();
        assertThat(usuarios.admin("b")).isFalse();
        assertThat(usuarios.admin("c")).isFalse();
        assertThat(usuarios.admin("d")).isFalse();
    }
}
