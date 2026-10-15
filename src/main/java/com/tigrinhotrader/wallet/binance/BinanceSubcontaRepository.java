package com.tigrinhotrader.wallet.binance;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BinanceSubcontaRepository extends JpaRepository<BinanceSubconta, UUID> {
    Optional<BinanceSubconta> findByUsuarioId(String usuarioId);
}
