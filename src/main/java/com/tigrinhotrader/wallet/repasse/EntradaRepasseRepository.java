package com.tigrinhotrader.wallet.repasse;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntradaRepasseRepository extends JpaRepository<EntradaRepasse, UUID> {

    /** Entradas na fila (ainda nao repassadas), da mais antiga para a mais nova. */
    List<EntradaRepasse> findByRepasseIdIsNullOrderByCriadaEmAsc();

    List<EntradaRepasse> findByRepasseId(UUID repasseId);
}
