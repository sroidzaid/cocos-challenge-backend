package com.cocos.challenge.orders.domain;

/**
 * Values of {@code orders.side}. BUY/SELL trade instruments; CASH_IN/CASH_OUT are incoming/outgoing transfers,
 * modelled as orders on the currency instrument (explicit in the challenge statement).
 */
public enum OrderSide {
    BUY,
    SELL,
    CASH_IN,
    CASH_OUT;

    public boolean isTransfer() {
        return this == CASH_IN || this == CASH_OUT;
    }
}
