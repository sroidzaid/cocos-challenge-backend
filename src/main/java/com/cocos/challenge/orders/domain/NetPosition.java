package com.cocos.challenge.orders.domain;

/**
 * Net quantity of an instrument from the user's FILLED BUY/SELL orders, exactly as persisted:
 * it can be zero, and it can be negative when the provided data contains more sells than buys
 * (e.g. BMA for user 1). Interpreting such positions is a business decision, not a persistence one.
 */
public record NetPosition(int instrumentId, long quantity) {

    public boolean isNegative() {
        return quantity < 0;
    }
}
