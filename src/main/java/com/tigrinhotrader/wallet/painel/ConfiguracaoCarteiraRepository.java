package com.tigrinhotrader.wallet.painel;

import org.springframework.data.jpa.repository.JpaRepository;

/** A linha unica (id 1) do que o painel configurou para a carteira. */
public interface ConfiguracaoCarteiraRepository extends JpaRepository<ConfiguracaoCarteira, Integer> {
}
