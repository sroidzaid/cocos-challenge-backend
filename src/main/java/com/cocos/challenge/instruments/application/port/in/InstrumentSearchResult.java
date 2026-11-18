package com.cocos.challenge.instruments.application.port.in;

import java.math.BigDecimal;
import java.util.Objects;

import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.marketdata.domain.MarketQuote;

/**
 * An instrument found by a search, with its latest quote.
 *
 * @param latestQuote {@code null} when the instrument has no market data
 */
public record InstrumentSearchResult(Instrument instrument, MarketQuote latestQuote) {

    public InstrumentSearchResult {
        Objects.requireNonNull(instrument, "instrument");
    }

    /** {@code close} of the latest quote, or {@code null} without market data. */
    public BigDecimal lastPrice() {
        return latestQuote == null ? null : latestQuote.lastPrice();
    }

    /** Daily return in percent, or {@code null} when it cannot be computed. */
    public BigDecimal dailyReturnPercent() {
        return latestQuote == null ? null : latestQuote.dailyReturnPercent();
    }
}
