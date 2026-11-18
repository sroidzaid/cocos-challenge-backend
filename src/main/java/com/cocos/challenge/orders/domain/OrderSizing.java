package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.cocos.challenge.shared.domain.MonetaryPolicy;

/** How the client sized the order: a whole number of shares or an amount of ARS to invest. */
public sealed interface OrderSizing {

    /**
     * Whole shares this sizing represents at the execution price. Never rounds up: fractional shares are not allowed
     * and the investment must not exceed the requested amount.
     *
     * @return the number of shares, possibly 0 (amount below one share) and capped at {@link Long#MAX_VALUE}
     */
    long sharesAt(BigDecimal price);

    record Quantity(int shares) implements OrderSizing {

        public Quantity {
            if (shares <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }

        @Override
        public long sharesAt(BigDecimal price) {
            return shares;
        }
    }

    record Amount(BigDecimal value) implements OrderSizing {

        private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

        public Amount {
            Objects.requireNonNull(value, "value");
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }

        /** {@code floor(amount / price)} ({@link MonetaryPolicy#sharesFor}). */
        @Override
        public long sharesAt(BigDecimal price) {
            BigDecimal shares = MonetaryPolicy.sharesFor(value, price);
            return shares.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : shares.longValueExact();
        }
    }
}
