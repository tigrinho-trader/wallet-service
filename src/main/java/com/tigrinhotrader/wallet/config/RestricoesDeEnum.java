package com.tigrinhotrader.wallet.config;

import com.tigrinhotrader.wallet.dominio.TipoTransacao;
import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * O {@code ddl-auto: update} cria a restricao CHECK de cada coluna de enum uma unica vez e nunca a atualiza.
 * Num banco que ja existia, um valor novo do enum (ex.: {@code COMPRA_PIX}) seria recusado pelo Postgres.
 * Na subida, refaz essas restricoes com os valores atuais do codigo.
 */
@Component
public class RestricoesDeEnum implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RestricoesDeEnum.class);

    record ColunaEnum(String tabela, String coluna, Class<? extends Enum<?>> tipo) {

        String restricao() {
            return tabela + "_" + coluna + "_check";
        }

        List<String> sql() {
            String valores = Arrays.stream(tipo.getEnumConstants())
                    .map(v -> "'" + v.name() + "'")
                    .collect(Collectors.joining(", "));
            return List.of(
                    "ALTER TABLE " + tabela + " DROP CONSTRAINT IF EXISTS " + restricao(),
                    "ALTER TABLE " + tabela + " ADD CONSTRAINT " + restricao()
                            + " CHECK (" + coluna + " IN (" + valores + "))");
        }
    }

    static final List<ColunaEnum> COLUNAS = List.of(
            new ColunaEnum("transacoes", "tipo", TipoTransacao.class),
            new ColunaEnum("compras_pix", "status", StatusCobranca.class));

    private final DataSource dataSource;

    public RestricoesDeEnum(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        try (Connection conexao = dataSource.getConnection()) {
            // Os nomes das restricoes sao os que o Hibernate gera no Postgres; no H2 dos testes nao se aplica.
            if (!"PostgreSQL".equals(conexao.getMetaData().getDatabaseProductName())) {
                return;
            }
            try (Statement comando = conexao.createStatement()) {
                for (ColunaEnum c : COLUNAS) {
                    for (String sql : c.sql()) {
                        comando.execute(sql);
                    }
                    LOG.info("Restricao {} atualizada com os valores de {}", c.restricao(), c.tipo().getSimpleName());
                }
            }
        }
    }
}
