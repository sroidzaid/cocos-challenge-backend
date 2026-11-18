package com.cocos.challenge.orders.application.port.in;

import java.util.Objects;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.RejectionReason;

/**
 * Result of placing an order, exactly as it was at creation.
 *
 * @param order           the order with its status at creation (a replay keeps it even if the order was cancelled
 *                        later)
 * @param rejectionReason only for REJECTED orders
 * @param replayed        {@code true} when this is the stored result of an earlier request with the same idempotency
 *                        key (no new order was placed)
 */
public record PlacedOrder(Order order, RejectionReason rejectionReason, boolean replayed) {

    public PlacedOrder {
        Objects.requireNonNull(order, "order");
    }
}
