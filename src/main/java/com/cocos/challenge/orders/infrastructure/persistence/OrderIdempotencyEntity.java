package com.cocos.challenge.orders.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mirror of {@code order_idempotency} (db/migration/V2). Insert-only: rows are never updated. {@code user_id} and
 * {@code order_id} are scalar ids, like in {@link OrderEntity}; integrity is enforced by the database.
 */
@Entity
@Table(name = "order_idempotency")
public class OrderIdempotencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Integer userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Integer orderId;

    @Column(name = "order_status", nullable = false, updatable = false, length = 20)
    private String orderStatus;

    @Column(name = "rejection_reason", updatable = false, length = 30)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderIdempotencyEntity() {
        // JPA
    }

    OrderIdempotencyEntity(Integer userId, UUID idempotencyKey, String requestHash, Integer orderId,
            String orderStatus, String rejectionReason, LocalDateTime createdAt) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt;
    }

    Integer getUserId() {
        return userId;
    }

    UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestHash() {
        return requestHash;
    }

    Integer getOrderId() {
        return orderId;
    }

    String getOrderStatus() {
        return orderStatus;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
