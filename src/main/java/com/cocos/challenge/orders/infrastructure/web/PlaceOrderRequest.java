package com.cocos.challenge.orders.infrastructure.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import org.hibernate.validator.group.GroupSequenceProvider;
import org.hibernate.validator.spi.group.DefaultGroupSequenceProvider;

/**
 * Body of {@code POST /orders}. Field constraints follow the original schema ({@code size INT},
 * {@code price NUMERIC(10,2)}); nothing is rounded silently. Cross-field rules are {@link AssertTrue} properties.
 * Rules that depend on the side are in the {@link Trade} and {@link Transfer} groups, checked after the common ones.
 */
@Schema(name = "PlaceOrderRequest", description = "BUY/SELL: instrumentId, type and exactly one of quantity or "
        + "amount; price only for LIMIT. CASH_IN/CASH_OUT: only amount, in whole pesos")
@GroupSequenceProvider(PlaceOrderRequest.SideGroups.class)
record PlaceOrderRequest(
        @Schema(description = "Instrument id (see GET /api/v1/instruments); BUY/SELL only", example = "47",
                nullable = true)
        @NotNull(groups = Trade.class)
        @Null(groups = Transfer.class, message = "not applicable to CASH_IN/CASH_OUT")
        @Min(1)
        Integer instrumentId,
        @Schema(description = "BUY, SELL, CASH_IN (deposit) or CASH_OUT (withdrawal)", example = "BUY")
        @NotNull
        OrderSide side,
        @Schema(description = "MARKET (executes at the last close) or LIMIT (stays NEW at the given price); "
                + "BUY/SELL only", example = "LIMIT", nullable = true)
        @NotNull(groups = Trade.class)
        @Null(groups = Transfer.class, message = "not applicable to CASH_IN/CASH_OUT")
        OrderType type,
        @Schema(description = "Whole number of shares; BUY/SELL only", example = "10", nullable = true)
        @Min(1)
        @Null(groups = Transfer.class, message = "not applicable to CASH_IN/CASH_OUT")
        Integer quantity,
        @Schema(description = "BUY/SELL: ARS to invest, shares = floor(amount / price). CASH_IN/CASH_OUT: ARS to "
                + "deposit/withdraw, a whole number without decimals up to 2147483647", example = "10000",
                nullable = true)
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2)
        @NotNull(groups = Transfer.class)
        @Digits(groups = Transfer.class, integer = 10, fraction = 0,
                message = "must be a whole number of pesos, without decimals")
        @DecimalMax(groups = Transfer.class, value = "2147483647")
        BigDecimal amount,
        @Schema(description = "Limit price, required for LIMIT and not allowed otherwise", example = "900.50",
                nullable = true)
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
        @Null(groups = Transfer.class, message = "not applicable to CASH_IN/CASH_OUT")
        BigDecimal price) {

    /** Constraints of BUY/SELL orders. */
    interface Trade {
    }

    /** Constraints of CASH_IN/CASH_OUT transfers. */
    interface Transfer {
    }

    @JsonIgnore
    @AssertTrue(message = "exactly one of quantity or amount is required")
    boolean isQuantityOrAmount() {
        return (quantity == null) != (amount == null);
    }

    @JsonIgnore
    @AssertTrue(message = "price is required for LIMIT orders and not allowed for MARKET orders")
    boolean isPriceMatchingType() {
        return type == null || (type == OrderType.LIMIT) == (price != null);
    }

    PlaceOrderCommand toCommand(int userId, UUID idempotencyKey) {
        OrderSizing sizing = quantity != null ? new OrderSizing.Quantity(quantity) : new OrderSizing.Amount(amount);
        return new PlaceOrderCommand(userId, instrumentId, side, type, sizing, price, idempotencyKey);
    }

    /** Common constraints first; then those of the side (none while the side is unknown). */
    public static final class SideGroups implements DefaultGroupSequenceProvider<PlaceOrderRequest> {

        public SideGroups() {
            // instantiated reflectively by Hibernate Validator
        }

        @Override
        public List<Class<?>> getValidationGroups(Class<?> klass, PlaceOrderRequest request) {
            List<Class<?>> groups = new ArrayList<>();
            groups.add(PlaceOrderRequest.class);
            if (request != null && request.side() != null) {
                groups.add(request.side().isTransfer() ? Transfer.class : Trade.class);
            }
            return groups;
        }
    }
}
