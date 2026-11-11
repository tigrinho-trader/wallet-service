package com.tigrinhotrader.wallet.saque;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaquePixRepository extends JpaRepository<SaquePix, UUID> {

    List<SaquePix> findTop20ByUsuarioIdOrderByCriadoEmDesc(String usuarioId);
}
