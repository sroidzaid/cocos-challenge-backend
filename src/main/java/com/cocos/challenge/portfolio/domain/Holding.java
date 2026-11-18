package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;

/**
 * Net position of an instrument with its cost basis.
 *
 * @param quantity    net shares; negative when the history sells more than it buys (inconsistent data)
 * @param averageCost weighted average purchase price per share, unrounded; {@code null} when it is not defined
 *                    (quantity zero or negative, see {@link PositionCalculator})
 */
public record Holding(int instrumentId, long quantity, BigDecimal averageCost) {

    public Holding {
        if (quantity <= 0 && averageCost != null) {
            throw new IllegalArgumentException("A non-positive position has no average cost");
        }
        if (quantity > 0 && averageCost == null) {
            throw new IllegalArgumentException("A positive position must have an average cost");
        }
    }

    public boolean isNegative() {
        return quantity < 0;
    }
}
