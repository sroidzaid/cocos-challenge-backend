package com.cocos.challenge.orders.application.port.out;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.RejectionReason;

/**
 * Immutable record of an order created with an idempotency key: the request fingerprint plus the minimal snapshot of
 * the creation result. Together with the order's immutable columns it rebuilds the original response even after the
 * order status changed (e.g. NEW, later CANCELLED).
 *
 * @param requestHash  lowercase hex SHA-256 of the canonical request
 * @param orderStatus  status at creation: FILLED, NEW or REJECTED
 * @param rejectionReason only for REJECTED (not stored in {@code orders})
 */
public record IdempotencyRecord(
        int userId,
        UUID idempotencyKey,
        String requestHash,
        int orderId,
        OrderStatus orderStatus,
        RejectionReason rejectionReason,
        LocalDateTime createdAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(orderStatus, "orderStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        if (orderStatus == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("An order is never created CANCELLED");
        }
        if ((orderStatus == OrderStatus.REJECTED) != (rejectionReason != null)) {
            throw new IllegalArgumentException("A rejection reason is required for REJECTED and only for REJECTED");
        }
    }
}
