package com.cocos.challenge.portfolio.domain;

/** How reliable the valuation of a position is. Every status other than {@link #OK} also produces a warning. */
public enum ValuationStatus {

    /** Valued at the latest close, with average cost and unrealized return. */
    OK,

    /** No market data: no market value nor return, and the position is left out of the total account value. */
    NO_MARKET_PRICE,

    /**
     * More shares sold than bought (inconsistent data). Valued at the latest close (negative market value, included
     * in the total because the cash of the sale is already counted), without average cost nor return.
     */
    NEGATIVE_QUANTITY
}
