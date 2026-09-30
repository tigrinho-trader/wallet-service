package com.tigrinhotrader.wallet.dominio;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransacaoRepository extends JpaRepository<Transacao, UUID> {

    List<Transacao> findByUsuarioIdOrderByCriadaEmDesc(String usuarioId);

    boolean existsByReferencia(String referencia);
}
