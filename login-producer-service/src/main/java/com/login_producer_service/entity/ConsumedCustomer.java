package com.login_producer_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "customer_consumed")
public class ConsumedCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "original_created_at")
    private Long originalCreatedAt;

    public ConsumedCustomer() {}

    public ConsumedCustomer(String payload, Instant consumedAt) {
        this.payload = payload;
        this.consumedAt = consumedAt;
    }

    public ConsumedCustomer(String payload, Instant consumedAt, Long originalCreatedAt) {
        this.payload = payload;
        this.consumedAt = consumedAt;
        this.originalCreatedAt = originalCreatedAt;
    }

    public Long getId() { return id; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public Long getOriginalCreatedAt() { return originalCreatedAt; }
    public void setOriginalCreatedAt(Long originalCreatedAt) { this.originalCreatedAt = originalCreatedAt; }
}
