package com.tigrinhotrader.wallet.binance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "binance_subcontas")
public class BinanceSubconta {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 128)
    private String usuarioId;
    @Column(nullable = false, unique = true, length = 64)
    private String subaccountId;
    @Column(nullable = false, length = 160)
    private String email;
    @Column(nullable = false, length = 32)
    private String tag;
    @Column(nullable = false)
    private Instant criadaEm;

    protected BinanceSubconta() { }

    public BinanceSubconta(String usuarioId, String subaccountId, String email, String tag, Instant criadaEm) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.subaccountId = subaccountId;
        this.email = email;
        this.tag = tag;
        this.criadaEm = criadaEm;
    }

    public String getUsuarioId() { return usuarioId; }
    public String getSubaccountId() { return subaccountId; }
    public String getEmail() { return email; }
    public String getTag() { return tag; }
    public Instant getCriadaEm() { return criadaEm; }
}
