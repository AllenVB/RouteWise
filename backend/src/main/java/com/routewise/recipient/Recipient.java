package com.routewise.recipient;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Recipient profile — one per {@code users} row with role RECIPIENT. */
@Entity
@Table(name = "recipients")
public class Recipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "default_address_id")
    private Long defaultAddressId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Recipient() {}

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getDefaultAddressId() { return defaultAddressId; }
}
