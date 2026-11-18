package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What the user can still commit, read under the account lock.
 *
 * @param cash   cash balance minus the cash reserved by NEW BUY orders (same formula as the portfolio)
 * @param shares net FILLED quantity of the instrument minus the shares reserved by its NEW SELL orders; can be
 *               negative with inconsistent data (BMA in the seed)
 */
public record Availability(BigDecimal cash, long shares) {

    public Availability {
        Objects.requireNonNull(cash, "cash");
    }

    public static Availability of(CashBalance balance, Reservations reservations, long netQuantity, int instrumentId) {
        return new Availability(balance.amount().subtract(reservations.cash()),
                netQuantity - reservations.sharesOf(instrumentId));
    }
}
