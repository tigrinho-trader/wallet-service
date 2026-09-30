package com.tigrinhotrader.wallet.mensageria;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Evento ordem.executada publicado pelo trading-service (so os campos que a carteira usa). */
public record OrdemExecutadaEvento(UUID ordemId, String usuarioId, String simbolo, String tipo,
                                   BigDecimal valor, String resultado, BigDecimal valorLiquido,
                                   Instant executadaEm) {
}
