package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Cash (ARS) from the user's FILLED orders: CASH_IN and SELL add {@code size * price}, CASH_OUT and BUY subtract it.
 * Exact value (integer size times a 2-decimal price), never rounded. Pending (NEW) orders are not considered here.
 */
public record CashBalance(BigDecimal amount) {

    public CashBalance {
        Objects.requireNonNull(amount, "amount");
    }
}
