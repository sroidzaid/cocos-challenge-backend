package com.cocos.challenge.orders.domain;

/**
 * Why a valid order was persisted as REJECTED. Returned to the client when the order is created; not persisted,
 * because the original schema has no column for it.
 */
public enum RejectionReason {

    /**
     * BUY: size * price exceeds the available cash (cash balance minus cash reserved by NEW BUY orders).
     * CASH_OUT: the amount exceeds that same available cash.
     */
    INSUFFICIENT_FUNDS,

    /** SELL: size exceeds the available shares (net FILLED quantity minus shares reserved by NEW SELL orders). */
    INSUFFICIENT_HOLDINGS
}
