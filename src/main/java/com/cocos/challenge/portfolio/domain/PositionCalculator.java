package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.shared.domain.MonetaryPolicy;

/**
 * Rebuilds positions and their weighted average cost (decision D1) from FILLED BUY/SELL orders, replayed in a
 * deterministic chronological order ({@code datetime}, then {@code id}). Other statuses and cash transfers are ignored.
 * <p>
 * Conservative cost-basis policy, never inventing a cost that the history does not support:
 * <ul>
 *     <li>BUY on a flat or long position: {@code avg = (avg*qty + price*size) / (qty + size)}, unrounded
 *     ({@link MonetaryPolicy#MATH});</li>
 *     <li>SELL leaving a long position: the average is kept (partial sale);</li>
 *     <li>SELL leaving the position at zero: the average is reset, the next BUY starts a new one;</li>
 *     <li>SELL leaving the position negative (oversold, inconsistent data): there is no average;</li>
 *     <li>BUY on a negative position: no average while it stays at or below zero; when it crosses to positive it
 *     is equivalent to closing at zero and buying the excess, so every share held comes from that BUY and the
 *     average is its price.</li>
 * </ul>
 */
public final class PositionCalculator {

    private static final Comparator<Order> CHRONOLOGICAL = Comparator
            .comparing(Order::datetime)
            .thenComparing(Order::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private PositionCalculator() {
    }

    /** Non-zero positions, ordered by instrument id. */
    public static List<Holding> holdings(List<Order> orders) {
        Map<Integer, Accumulator> byInstrument = new TreeMap<>();
        orders.stream()
                .filter(order -> order.status() == OrderStatus.FILLED)
                .filter(order -> order.side() == OrderSide.BUY || order.side() == OrderSide.SELL)
                .sorted(CHRONOLOGICAL)
                .forEach(order -> byInstrument.computeIfAbsent(order.instrumentId(), id -> new Accumulator())
                        .apply(order));
        return byInstrument.entrySet().stream()
                .filter(entry -> entry.getValue().quantity != 0)
                .map(entry -> new Holding(entry.getKey(), entry.getValue().quantity, entry.getValue().averageCost))
                .toList();
    }

    private static final class Accumulator {

        private long quantity;
        private BigDecimal averageCost;

        void apply(Order order) {
            requireValid(order);
            long size = order.size();
            if (order.side() == OrderSide.BUY) {
                buy(size, order.price());
            } else {
                sell(size);
            }
        }

        private void buy(long size, BigDecimal price) {
            long newQuantity = quantity + size;
            if (quantity > 0) {
                averageCost = averageCost.multiply(BigDecimal.valueOf(quantity))
                        .add(price.multiply(BigDecimal.valueOf(size)))
                        .divide(BigDecimal.valueOf(newQuantity), MonetaryPolicy.MATH);
            } else {
                // Flat: a new cost basis starts. Negative: only the shares above zero are held, all bought at price.
                averageCost = newQuantity > 0 ? price : null;
            }
            quantity = newQuantity;
        }

        private void sell(long size) {
            quantity -= size;
            if (quantity <= 0) {
                averageCost = null;
            }
        }

        private static void requireValid(Order order) {
            if (order.size() <= 0 || order.price() == null || order.price().signum() <= 0) {
                throw new IllegalStateException("FILLED order id=" + order.id() + " has an invalid size or price"
                        + "; refusing to compute positions");
            }
        }
    }
}
