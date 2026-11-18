package com.cocos.challenge.instruments.domain;

import java.util.Objects;

/**
 * Tradable instrument or currency. {@code ticker} and {@code name} are nullable in the provided schema.
 */
public record Instrument(int id, String ticker, String name, InstrumentType type) {

    /** Ticker of the local currency (cash), identified by ticker + type, never by a fixed id. */
    public static final String LOCAL_CURRENCY_TICKER = "ARS";

    public Instrument {
        Objects.requireNonNull(type, "type");
    }

    public boolean isCurrency() {
        return type == InstrumentType.MONEDA;
    }
}
