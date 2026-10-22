package com.routewise.address;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Address() {}

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
}
