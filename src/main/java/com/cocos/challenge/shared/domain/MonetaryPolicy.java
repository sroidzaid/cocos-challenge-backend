package com.cocos.challenge.shared.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Single place for monetary arithmetic rules.
 * <ul>
 *     <li>Amounts are exact: shares (integers) times prices (2 decimals) need no rounding, so they are never rounded.</li>
 *     <li>Divisions (percentages, average cost) use {@link #MATH} internally and are rounded only for presentation,
 *     with {@link #PRESENTATION_ROUNDING}.</li>
 *     <li>Share quantities derived from an amount are truncated ({@link RoundingMode#DOWN}): fractional shares are
 *     not allowed and the investment must never exceed the requested amount.</li>
 * </ul>
 */
public final class MonetaryPolicy {

    /** Precision for intermediate divisions (34 significant digits). */
    public static final MathContext MATH = MathContext.DECIMAL128;

    /** Percentages are exposed with 2 decimals. */
    public static final int PERCENT_SCALE = 2;

    /** Prices and amounts are exposed with 2 decimals (same as NUMERIC(10,2)). */
    public static final int MONEY_SCALE = 2;

    /** Banker's rounding for presented values: unbiased when many values are rounded. */
    public static final RoundingMode PRESENTATION_ROUNDING = RoundingMode.HALF_EVEN;

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private MonetaryPolicy() {
    }

    /**
     * Relative change from {@code from} to {@code to}, in percent, rounded to {@link #PERCENT_SCALE} decimals.
     *
     * @return {@code null} when it is undefined (missing value or {@code from} equal to zero)
     */
    public static BigDecimal percentChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) {
            return null;
        }
        return to.subtract(from)
                .divide(from, MATH)
                .multiply(ONE_HUNDRED)
                .setScale(PERCENT_SCALE, PRESENTATION_ROUNDING);
    }

    /** Maximum whole number of shares that {@code amount} can buy at {@code price} (truncated, never rounded up). */
    public static BigDecimal sharesFor(BigDecimal amount, BigDecimal price) {
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        return amount.divide(price, 0, RoundingMode.DOWN);
    }

    /** Presentation of a derived monetary value (e.g. an average cost). Exact amounts do not need it. */
    public static BigDecimal toMoneyScale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, PRESENTATION_ROUNDING);
    }
}
