package com.cocos.challenge.marketdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.cocos.challenge.shared.domain.MonetaryPolicy;

/**
 * Daily quote of an instrument, in ARS. {@code close} is the last price; {@code close} and {@code previousClose}
 * are the inputs of the daily return. Values are exactly as stored (NUMERIC(10,2)); either may be null in the
 * provided schema. open/high/low are persisted but not part of the domain (no requirement uses them).
 */
public record MarketQuote(int instrumentId, LocalDate date, BigDecimal close, BigDecimal previousClose) {

    /** Last price of the instrument: the close of its most recent quote (challenge statement). */
    public BigDecimal lastPrice() {
        return close;
    }

    /**
     * Daily return in percent: {@code (close - previousClose) / previousClose * 100}, 2 decimals, HALF_EVEN.
     *
     * @return {@code null} when it cannot be computed (missing close/previousClose or previousClose equal to zero)
     */
    public BigDecimal dailyReturnPercent() {
        return MonetaryPolicy.percentChange(previousClose, close);
    }
}
