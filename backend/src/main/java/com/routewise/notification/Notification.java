package com.routewise.notification;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A message to a user. Schema owned by Liquibase ({@code 006-notifications}).
 *
 * <p>The payload carries data, not text: the screen and the e-mail render the
 * wording, so a wording change never needs a data migration.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum Type { ETA, ERTELEME, DOGRULAMA, ROTA_DEGISTI }

    public enum Channel { IN_APP, EMAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "shipment_id")
    private Long shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {}

    public Notification(Long userId, Long shipmentId, Type type, Channel channel,
                        Map<String, Object> payload, Instant sentAt) {
        this.userId = userId;
        this.shipmentId = shipmentId;
        this.type = type;
        this.channel = channel;
        this.payload = payload;
        this.sentAt = sentAt;
        this.createdAt = sentAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getShipmentId() { return shipmentId; }
    public Type getType() { return type; }
    public Channel getChannel() { return channel; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getSentAt() { return sentAt; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
