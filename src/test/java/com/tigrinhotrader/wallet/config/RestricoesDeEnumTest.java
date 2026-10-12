package com.tigrinhotrader.wallet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.wallet.config.RestricoesDeEnum.ColunaEnum;
import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RestricoesDeEnumTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection conexao = mock(Connection.class);
    private final DatabaseMetaData metadados = mock(DatabaseMetaData.class);
    private final Statement comando = mock(Statement.class);

    private void banco(String produto) throws SQLException {
        when(dataSource.getConnection()).thenReturn(conexao);
        when(conexao.getMetaData()).thenReturn(metadados);
        when(metadados.getDatabaseProductName()).thenReturn(produto);
        when(conexao.createStatement()).thenReturn(comando);
    }

    @Test
    void sqlRefazARestricaoComTodosOsValoresDoEnum() {
        List<String> sql = new ColunaEnum("transacoes", "tipo", TipoTransacao.class).sql();

        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).isEqualTo("ALTER TABLE transacoes DROP CONSTRAINT IF EXISTS transacoes_tipo_check");
        assertThat(sql.get(1))
                .startsWith("ALTER TABLE transacoes ADD CONSTRAINT transacoes_tipo_check CHECK (tipo IN (")
                .contains("'COMPRA_PIX'");
        for (TipoTransacao t : TipoTransacao.values()) {
            assertThat(sql.get(1)).contains("'" + t.name() + "'");
        }
    }

    @Test
    void cobreAsColunasDeEnumDaCarteira() {
        assertThat(RestricoesDeEnum.COLUNAS).extracting(ColunaEnum::restricao)
                .containsExactly("transacoes_tipo_check", "compras_pix_status_check");
    }

    @Test
    void noPostgresApagaERecriaCadaRestricao() throws Exception {
        banco("PostgreSQL");

        new RestricoesDeEnum(dataSource).run(null);

        InOrder ordem = inOrder(comando);
        for (ColunaEnum c : RestricoesDeEnum.COLUNAS) {
            for (String sql : c.sql()) {
                ordem.verify(comando).execute(sql);
            }
        }
        verify(conexao).close();
    }

    @Test
    void foraDoPostgresNaoMexeEmNada() throws Exception {
        banco("H2");

        new RestricoesDeEnum(dataSource).run(null);

        verify(comando, never()).execute(anyString());
        verify(conexao).close();
    }
}
