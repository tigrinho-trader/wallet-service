package com.tigrinhotrader.wallet.repasse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepasseRepository extends JpaRepository<Repasse, UUID> {

    /** O repasse em andamento (no maximo um por vez). */
    Optional<Repasse> findFirstByEtapaNotInOrderByCriadoEmAsc(Collection<Repasse.Etapa> finalizadas);

    Optional<Repasse> findFirstByOrderByCriadoEmDesc();
}
