package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Decides the status of a new order (pure functions, no I/O). BUY/SELL ({@link #decide}; transfers:
 * {@link #decideTransfer}):
 * <table>
 *     <caption>Rules</caption>
 *     <tr><th>Side</th><th>Check</th><th>MARKET</th><th>LIMIT</th><th>Otherwise</th></tr>
 *     <tr><td>BUY</td><td>{@code size * price <= available cash}</td><td>FILLED</td><td>NEW (reserves cash)</td>
 *         <td>REJECTED, INSUFFICIENT_FUNDS</td></tr>
 *     <tr><td>SELL</td><td>{@code size <= available shares}</td><td>FILLED</td><td>NEW (reserves shares)</td>
 *         <td>REJECTED, INSUFFICIENT_HOLDINGS</td></tr>
 * </table>
 * Amounts are exact (integer size times a 2-decimal price), so the comparison needs no rounding. A SELL can never
 * open a negative position: with zero or negative available shares it is rejected.
 */
public final class OrderPlacementPolicy {

    private OrderPlacementPolicy() {
    }

    public static PlacementDecision decide(OrderSide side, OrderType type, int size, BigDecimal price,
            Availability availability) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(price, "price");
        if (size <= 0 || price.signum() <= 0) {
            throw new IllegalArgumentException("size and price must be positive");
        }
        boolean available = switch (side) {
            case BUY -> price.multiply(BigDecimal.valueOf(size)).compareTo(availability.cash()) <= 0;
            case SELL -> size <= availability.shares();
            case CASH_IN, CASH_OUT -> throw new IllegalArgumentException("Not a trade: " + side);
        };
        if (!available) {
            return PlacementDecision.rejected(
                    side == OrderSide.BUY ? RejectionReason.INSUFFICIENT_FUNDS : RejectionReason.INSUFFICIENT_HOLDINGS);
        }
        return PlacementDecision.accepted(type == OrderType.MARKET ? OrderStatus.FILLED : OrderStatus.NEW);
    }

    /**
     * Decides the status of a new transfer. CASH_IN is always FILLED. CASH_OUT is FILLED when
     * {@code amount <= available cash} (cash balance minus the cash reserved by NEW BUY orders, the same figure a BUY
     * is checked against), otherwise REJECTED with INSUFFICIENT_FUNDS. Transfers never stay NEW.
     */
    public static PlacementDecision decideTransfer(OrderSide side, int amount, Availability availability) {
        Objects.requireNonNull(availability, "availability");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        boolean available = switch (side) {
            case CASH_IN -> true;
            case CASH_OUT -> BigDecimal.valueOf(amount).compareTo(availability.cash()) <= 0;
            case BUY, SELL -> throw new IllegalArgumentException("Not a transfer: " + side);
        };
        return available
                ? PlacementDecision.accepted(OrderStatus.FILLED)
                : PlacementDecision.rejected(RejectionReason.INSUFFICIENT_FUNDS);
    }
}
