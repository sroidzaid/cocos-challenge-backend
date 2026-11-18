package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * What the user's pending (NEW) orders hold back (decision D2): a NEW BUY reserves {@code size * price} of cash and a
 * NEW SELL reserves {@code size} shares of its instrument. Cancelling the order releases the reservation.
 * Used both to show the available cash (portfolio) and to validate new orders, so the rule lives in one place.
 *
 * @param cash               exact sum of {@code size * price} of NEW BUY orders, never rounded
 * @param sharesByInstrument shares reserved by NEW SELL orders, per instrument id (instruments without NEW SELL
 *                           orders are absent)
 */
public record Reservations(BigDecimal cash, Map<Integer, Long> sharesByInstrument) {

    public Reservations {
        Objects.requireNonNull(cash, "cash");
        sharesByInstrument = Map.copyOf(sharesByInstrument);
    }

    public static Reservations none() {
        return new Reservations(BigDecimal.ZERO, Map.of());
    }

    public long sharesOf(int instrumentId) {
        return sharesByInstrument.getOrDefault(instrumentId, 0L);
    }
}
