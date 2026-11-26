package com.routewise.address;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.Point;

/** Delivery address. Schema owned by Liquibase. */
@Entity
@Table(name = "addresses")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "address_text", nullable = false)
    private String addressText;

    private String district;
    private String neighbourhood;

    /** WGS84 point; GIST-indexed in the schema. */
    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "snapped_node_id")
    private Long snappedNodeId;

    @Column(name = "snap_distance_m")
    private BigDecimal snapDistanceM;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.TASLAK;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "change_count_today", nullable = false)
    private short changeCountToday = 0;

    /** Sayacın ait olduğu gün; başka bir günse sayaç sıfır sayılır. */
    @Column(name = "change_count_date")
    private LocalDate changeCountDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Address() {}

    public Address(Long recipientId, String addressText, String district,
                   String neighbourhood, Point location,
                   Long snappedNodeId, BigDecimal snapDistanceM) {
        this.recipientId = recipientId;
        this.addressText = addressText;
        this.district = district;
        this.neighbourhood = neighbourhood;
        this.location = location;
        this.snappedNodeId = snappedNodeId;
        this.snapDistanceM = snapDistanceM;
        // Yeni adres doğrudan kuyruğa girer: firma onaylamadan rotaya giremez.
        this.verificationStatus = VerificationStatus.DOGRULAMA_BEKLIYOR;
    }

    /** Firma onayı — beşinci katman. */
    public void verify(Long verifierUserId) {
        this.verificationStatus = VerificationStatus.DOGRULANDI;
        this.verifiedBy = verifierUserId;
        this.verifiedAt = Instant.now();
        this.rejectionReason = null;
        this.updatedAt = Instant.now();
    }

    public void reject(Long verifierUserId, String reason) {
        this.verificationStatus = VerificationStatus.REDDEDILDI;
        this.verifiedBy = verifierUserId;
        this.verifiedAt = Instant.now();
        this.rejectionReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Konum değişti — doğrulama sıfırlanır.
     *
     * <p>Bu projedeki en sessiz hata kaynağı burasıdır: doğrulanmış bir adres
     * değiştirilip durumu korunsaydı, firma onayladığını sanırken rota başka
     * bir noktaya çizilirdi. Değişiklik onayı geçersiz kılar.
     */
    public void relocate(String addressText, String district, String neighbourhood,
                         Point location, Long snappedNodeId, BigDecimal snapDistanceM,
                         LocalDate today) {
        this.addressText = addressText;
        this.district = district;
        this.neighbourhood = neighbourhood;
        this.location = location;
        this.snappedNodeId = snappedNodeId;
        this.snapDistanceM = snapDistanceM;
        this.verificationStatus = VerificationStatus.DOGRULAMA_BEKLIYOR;
        this.verifiedBy = null;
        this.verifiedAt = null;
        this.rejectionReason = null;
        this.changeCountToday = (short) (changesOn(today) + 1);
        this.changeCountDate = today;
        this.updatedAt = Instant.now();
    }

    /**
     * Verilen gün yapılmış değişiklik sayısı.
     *
     * <p>Ham sayaç dışarı açılmaz: tarihine bakmadan okunursa dünkü değişiklikler
     * bugünün limitinden düşer ve adres kalıcı olarak kilitlenir.
     */
    public int changesOn(LocalDate day) {
        return day.equals(changeCountDate) ? changeCountToday : 0;
    }

    public Long getId() { return id; }
    public Long getRecipientId() { return recipientId; }
    public String getAddressText() { return addressText; }
    public String getDistrict() { return district; }
    public String getNeighbourhood() { return neighbourhood; }
    public Point getLocation() { return location; }
    public Long getSnappedNodeId() { return snappedNodeId; }
    public BigDecimal getSnapDistanceM() { return snapDistanceM; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVerifiedBy() { return verifiedBy; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
