package com.tigrinhotrader.wallet.pix;

import com.tigrinhotrader.wallet.pix.ProvedorPix.StatusCobranca;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Compra de moedas por Pix. Nasce PENDENTE com a cobranca criada no provedor e so vira PAGA quando o
 * provedor confirma; as moedas entram na carteira uma unica vez (referencia = id da cobranca).
 */
@Entity
@Table(name = "compras_pix", indexes = @Index(name = "idx_compras_usuario", columnList = "usuarioId, criadaEm"))
public class CompraPix {

    @Id
    private UUID id;

    @Version
    private Long versao;

    @Column(nullable = false)
    private String usuarioId;

    @Column(nullable = false)
    private int moedas;

    @Column(nullable = false)
    private long centavos;

    @Column(nullable = false, unique = true, length = 64)
    private String cobrancaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusCobranca status;

    @Column(length = 2000)
    private String brCode;

    /** Imagem do QR Code em data URL (pode passar de 10 KB). */
    @Column(columnDefinition = "TEXT")
    private String brCodeBase64;

    private Instant expiraEm;

    @Column(nullable = false)
    private boolean devMode;

    @Column(nullable = false)
    private Instant criadaEm;

    private Instant pagaEm;

    protected CompraPix() {
        // JPA
    }

    public CompraPix(UUID id, String usuarioId, int moedas, ProvedorPix.CobrancaPix cobranca, Instant agora) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.moedas = moedas;
        this.centavos = cobranca.centavos();
        this.cobrancaId = cobranca.id();
        this.status = cobranca.status();
        this.brCode = cobranca.brCode();
        this.brCodeBase64 = cobranca.brCodeBase64();
        this.expiraEm = cobranca.expiraEm();
        this.devMode = cobranca.devMode();
        this.criadaEm = agora;
    }

    public boolean pendente() {
        return status == StatusCobranca.PENDENTE;
    }

    /** Aplica o status vindo do provedor. Devolve true se a compra acabou de ser paga. */
    public boolean atualizarStatus(StatusCobranca novo, Instant agora) {
        if (!pendente() || novo == StatusCobranca.PENDENTE) {
            return false;
        }
        status = novo;
        if (novo == StatusCobranca.PAGA) {
            pagaEm = agora;
            return true;
        }
        return false;
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public int getMoedas() {
        return moedas;
    }

    public long getCentavos() {
        return centavos;
    }

    public String getCobrancaId() {
        return cobrancaId;
    }

    public StatusCobranca getStatus() {
        return status;
    }

    public String getBrCode() {
        return brCode;
    }

    public String getBrCodeBase64() {
        return brCodeBase64;
    }

    public Instant getExpiraEm() {
        return expiraEm;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getPagaEm() {
        return pagaEm;
    }
}
