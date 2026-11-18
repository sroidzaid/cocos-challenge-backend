package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * An order as persisted in {@code orders}. Reads are lenient about values the seed may contain; new orders are
 * created with {@link #newTrade}, which enforces positive size and price. Placement rules live in
 * {@link OrderPlacementPolicy}.
 *
 * @param id       null until persisted
 * @param size     number of shares for BUY/SELL; amount in whole pesos for CASH_IN/CASH_OUT (observed convention)
 * @param price    price per share in ARS, NUMERIC(10,2); 1 for transfers in the provided data (observed convention)
 * @param datetime TIMESTAMP WITHOUT TIME ZONE, in the configured business time zone
 */
public record Order(
        Integer id,
        int userId,
        int instrumentId,
        OrderSide side,
        OrderType type,
        OrderStatus status,
        int size,
        BigDecimal price,
        LocalDateTime datetime) {

    /** Unit price of a transfer (1 ARS per peso), as in the provided data. */
    public static final BigDecimal TRANSFER_PRICE = new BigDecimal("1.00");

    public Order {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(datetime, "datetime");
    }

    /** A new BUY/SELL order, not yet persisted. */
    public static Order newTrade(int userId, int instrumentId, OrderSide side, OrderType type, OrderStatus status,
            int size, BigDecimal price, LocalDateTime datetime) {
        if (side != OrderSide.BUY && side != OrderSide.SELL) {
            throw new IllegalArgumentException("Not a trade: " + side);
        }
        if (size <= 0 || price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("size and price must be positive");
        }
        return new Order(null, userId, instrumentId, side, type, status, size, price, datetime);
    }

    /**
     * A new CASH_IN/CASH_OUT, not yet persisted, following the convention of the provided data: local currency
     * instrument, {@code size} = amount in whole pesos, {@code price} = 1, type MARKET.
     */
    public static Order newTransfer(int userId, int currencyInstrumentId, OrderSide side, OrderStatus status,
            int amount, LocalDateTime datetime) {
        if (!side.isTransfer()) {
            throw new IllegalArgumentException("Not a transfer: " + side);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return new Order(null, userId, currencyInstrumentId, side, OrderType.MARKET, status, amount, TRANSFER_PRICE,
                datetime);
    }

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, userId, instrumentId, side, type, newStatus, size, price, datetime);
    }
}
