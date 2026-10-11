package com.tigrinhotrader.wallet.pix;

import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompraPixRepository extends JpaRepository<CompraPix, UUID> {

    Optional<CompraPix> findByIdAndUsuarioId(UUID id, String usuarioId);

    long countByUsuarioIdAndStatus(String usuarioId, StatusCobranca status);
}
