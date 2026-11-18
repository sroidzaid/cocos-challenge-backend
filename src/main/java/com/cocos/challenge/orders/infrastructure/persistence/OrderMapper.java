package com.cocos.challenge.orders.infrastructure.persistence;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.infrastructure.persistence.MonetaryColumns;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;

/**
 * Entity &lt;-&gt; domain conversion. Strict on reads: an order whose stored values the application cannot interpret
 * makes the read fail (with the order id) instead of being skipped, because skipping it could silently produce
 * wrong balances. Strict on writes: monetary values that do not fit NUMERIC(10,2) are rejected, never rounded.
 */
final class OrderMapper {

    private OrderMapper() {
    }

    static Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(),
                required(e.getUserId(), "userid", e),
                required(e.getInstrumentId(), "instrumentid", e),
                parse(OrderSide.class, e.getSide(), "side", e),
                parse(OrderType.class, e.getType(), "type", e),
                parse(OrderStatus.class, e.getStatus(), "status", e),
                required(e.getSize(), "size", e),
                e.getPrice(),
                required(e.getDatetime(), "datetime", e));
    }

    static OrderEntity toNewEntity(Order order) {
        if (order.id() != null) {
            throw new IllegalArgumentException("Only new orders (without id) can be inserted");
        }
        return new OrderEntity(
                order.instrumentId(),
                order.userId(),
                order.size(),
                MonetaryColumns.requireFitsNumeric10x2(order.price(), "orders.price"),
                order.type().name(),
                order.side().name(),
                order.status().name(),
                order.datetime());
    }

    private static <T> T required(T value, String column, OrderEntity e) {
        if (value == null) {
            throw new PersistedDataIntegrityException("orders.id=" + e.getId() + " has a null " + column);
        }
        return value;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String column, OrderEntity e) {
        if (raw != null) {
            for (E candidate : type.getEnumConstants()) {
                if (candidate.name().equals(raw)) {
                    return candidate;
                }
            }
        }
        throw new PersistedDataIntegrityException(
                "orders.id=" + e.getId() + " has an unsupported " + column + ": " + raw);
    }
}
