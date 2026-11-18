package com.cocos.challenge.orders.infrastructure.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Order", description = "An order as persisted, with its resulting status")
record OrderResponse(
        @Schema(example = "12")
        int id,
        @Schema(example = "1")
        int userId,
        @Schema(description = "Traded instrument; the ARS currency instrument for CASH_IN/CASH_OUT", example = "47")
        int instrumentId,
        OrderSide side,
        OrderType type,
        @Schema(description = "FILLED (MARKET executed or transfer applied), NEW (LIMIT pending, reserving cash or "
                + "shares), REJECTED (not enough cash or shares) or CANCELLED")
        OrderStatus status,
        @Schema(description = "Number of shares; amount in whole pesos for CASH_IN/CASH_OUT", example = "10")
        int size,
        @Schema(description = "Execution price: last close for MARKET, limit price for LIMIT, 1.00 for transfers",
                example = "925.85")
        BigDecimal price,
        @Schema(example = "2026-11-12T10:15:00")
        LocalDateTime datetime,
        @Schema(description = "Only for REJECTED orders on creation; not persisted (no column in the schema)",
                nullable = true)
        RejectionReason rejectionReason) {

    static OrderResponse from(PlacedOrder placed) {
        return from(placed.order(), placed.rejectionReason());
    }

    static OrderResponse from(Order order) {
        return from(order, null);
    }

    private static OrderResponse from(Order order, RejectionReason reason) {
        return new OrderResponse(order.id(), order.userId(), order.instrumentId(), order.side(), order.type(),
                order.status(), order.size(), order.price(), order.datetime(), reason);
    }
}
