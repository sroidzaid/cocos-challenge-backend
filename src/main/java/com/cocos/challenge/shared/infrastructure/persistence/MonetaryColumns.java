package com.cocos.challenge.shared.infrastructure.persistence;

import java.math.BigDecimal;

/**
 * Guards values written to {@code NUMERIC(10,2)} columns. PostgreSQL silently rounds values with a larger scale
 * (the seed itself contains 1047.166 stored as 1047.17), so the application rejects them before writing instead of
 * letting the database round. Never rounds.
 */
public final class MonetaryColumns {

    private static final int SCALE = 2;
    private static final BigDecimal MAX = new BigDecimal("99999999.99");

    private MonetaryColumns() {
    }

    /**
     * @return the same value, unchanged
     * @throws IllegalArgumentException if the value has more than 2 decimals or does not fit NUMERIC(10,2)
     */
    public static BigDecimal requireFitsNumeric10x2(BigDecimal value, String column) {
        if (value == null) {
            return null;
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException(
                    column + " must have at most " + SCALE + " decimals (no silent rounding): " + value);
        }
        if (value.abs().compareTo(MAX) > 0) {
            throw new IllegalArgumentException(column + " exceeds the NUMERIC(10,2) range: " + value);
        }
        return value;
    }
}
