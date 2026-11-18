package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.cocos.challenge.shared.domain.MonetaryPolicy;

/**
 * A position valued at the latest close.
 *
 * @param averageCost        unrounded weighted average cost, {@code null} for non-positive positions
 * @param lastPrice          close of the latest quote, {@code null} without market data
 * @param marketValue        {@code quantity * lastPrice}, exact; {@code null} without market data
 * @param totalReturnPercent unrealized return of the current position over its cost basis:
 *                           {@code (lastPrice - averageCost) / averageCost * 100}, which equals
 *                           {@code (marketValue - cost) / cost * 100}; 2 decimals HALF_EVEN; {@code null} when it
 *                           cannot be computed reliably. Realized gains of past sales are already part of the cash.
 */
public record PositionValuation(
        int instrumentId,
        String ticker,
        String name,
        long quantity,
        BigDecimal averageCost,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal totalReturnPercent,
        ValuationStatus status) {

    public PositionValuation {
        Objects.requireNonNull(status, "status");
    }

    public static PositionValuation of(Holding holding, String ticker, String name, BigDecimal lastPrice) {
        BigDecimal marketValue = lastPrice == null ? null : lastPrice.multiply(BigDecimal.valueOf(holding.quantity()));
        BigDecimal totalReturn = holding.quantity() > 0 && holding.averageCost().signum() > 0
                ? MonetaryPolicy.percentChange(holding.averageCost(), lastPrice)
                : null;
        ValuationStatus status;
        if (lastPrice == null) {
            status = ValuationStatus.NO_MARKET_PRICE;
        } else if (holding.isNegative()) {
            status = ValuationStatus.NEGATIVE_QUANTITY;
        } else {
            status = ValuationStatus.OK;
        }
        return new PositionValuation(holding.instrumentId(), ticker, name, holding.quantity(), holding.averageCost(),
                lastPrice, marketValue, totalReturn, status);
    }

    public boolean isPriced() {
        return marketValue != null;
    }
}
