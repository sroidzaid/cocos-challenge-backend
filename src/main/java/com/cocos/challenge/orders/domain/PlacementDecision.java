package com.cocos.challenge.orders.domain;

import java.util.Objects;

/** Resulting status of a new order and, when REJECTED, the reason. */
public record PlacementDecision(OrderStatus status, RejectionReason reason) {

    public PlacementDecision {
        Objects.requireNonNull(status, "status");
        if ((status == OrderStatus.REJECTED) != (reason != null)) {
            throw new IllegalArgumentException("A reason is required for REJECTED and only for REJECTED");
        }
    }

    static PlacementDecision accepted(OrderStatus status) {
        return new PlacementDecision(status, null);
    }

    static PlacementDecision rejected(RejectionReason reason) {
        return new PlacementDecision(OrderStatus.REJECTED, reason);
    }
}
