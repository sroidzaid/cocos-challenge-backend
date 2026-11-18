package com.cocos.challenge.orders.domain;

/**
 * Values of {@code orders.status}. Only FILLED orders affect holdings and cash; NEW orders reserve cash (BUY) or
 * shares (SELL) until they are cancelled.
 * <pre>
 *   (new) --MARKET, available-----&gt; FILLED
 *   (new) --LIMIT,  available-----&gt; NEW --cancel--&gt; CANCELLED
 *   (new) --not enough cash/shares-&gt; REJECTED
 * </pre>
 * FILLED, REJECTED and CANCELLED are final. There is no NEW -&gt; FILLED transition (no matching engine).
 */
public enum OrderStatus {
    NEW,
    FILLED,
    REJECTED,
    CANCELLED;

    /** Only pending orders can be cancelled. */
    public boolean isCancellable() {
        return this == NEW;
    }
}
