package com.cocos.challenge.orders.application.port.in;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderType;

/**
 * Request to place an order: a BUY/SELL trade or a CASH_IN/CASH_OUT transfer.
 *
 * @param instrumentId   required for BUY/SELL; absent for transfers (they always use the local currency instrument)
 * @param type           required for BUY/SELL; absent for transfers
 * @param sizing         BUY/SELL: quantity or amount; transfers: amount in whole pesos (the {@code size INT} column)
 * @param limitPrice     required for LIMIT orders, absent otherwise (MARKET executes at the last close)
 * @param idempotencyKey client-generated key of this intention, scoped by user: retrying with the same key and the
 *                       same request returns the original result instead of placing another order
 */
public record PlaceOrderCommand(
        int userId,
        Integer instrumentId,
        OrderSide side,
        OrderType type,
        OrderSizing sizing,
        BigDecimal limitPrice,
        UUID idempotencyKey) {

    private static final BigDecimal MAX_TRANSFER_AMOUNT = BigDecimal.valueOf(Integer.MAX_VALUE);

    public PlaceOrderCommand {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(sizing, "sizing");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (side.isTransfer()) {
            requireValidTransfer(instrumentId, type, sizing, limitPrice);
        } else {
            requireValidTrade(instrumentId, type, limitPrice);
        }
    }

    /** Amount of a CASH_IN/CASH_OUT in whole pesos. */
    public int transferAmount() {
        if (!side.isTransfer()) {
            throw new IllegalStateException("Not a transfer: " + side);
        }
        return ((OrderSizing.Amount) sizing).value().intValueExact();
    }

    private static void requireValidTrade(Integer instrumentId, OrderType type, BigDecimal limitPrice) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(type, "type");
        if ((type == OrderType.LIMIT) != (limitPrice != null)) {
            throw new IllegalArgumentException("A limit price is required for LIMIT orders and only for them");
        }
        if (limitPrice != null && (limitPrice.signum() <= 0 || limitPrice.stripTrailingZeros().scale() > 2)) {
            throw new IllegalArgumentException("limit price must be positive with at most 2 decimals");
        }
    }

    private static void requireValidTransfer(Integer instrumentId, OrderType type, OrderSizing sizing,
            BigDecimal limitPrice) {
        if (instrumentId != null || type != null || limitPrice != null) {
            throw new IllegalArgumentException("Instrument, type and price do not apply to transfers");
        }
        if (!(sizing instanceof OrderSizing.Amount(BigDecimal amount))
                || amount.stripTrailingZeros().scale() > 0
                || amount.compareTo(MAX_TRANSFER_AMOUNT) > 0) {
            throw new IllegalArgumentException("A transfer requires an amount in whole pesos up to "
                    + Integer.MAX_VALUE);
        }
    }
}
