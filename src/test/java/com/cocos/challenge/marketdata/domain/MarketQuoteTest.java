package com.cocos.challenge.marketdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketQuoteTest {

    private static final LocalDate DAY = LocalDate.of(2023, 7, 14);

    @Test
    void lastPriceIsTheClose() {
        assertThat(quote("925.85", "921.80").lastPrice()).isEqualByComparingTo("925.85");
    }

    @Test
    void dailyReturnUsesCloseAndPreviousClose() {
        assertThat(quote("925.85", "921.80").dailyReturnPercent()).isEqualByComparingTo("0.44");
        assertThat(quote("1502.80", "1520.25").dailyReturnPercent()).isEqualByComparingTo("-1.15");
        assertThat(quote("565.00", "565.00").dailyReturnPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void dailyReturnIsUndefinedWhenDataIsMissing() {
        assertThat(quote("925.85", null).dailyReturnPercent()).isNull();
        assertThat(quote(null, "921.80").dailyReturnPercent()).isNull();
        assertThat(quote("925.85", "0").dailyReturnPercent()).isNull();
    }

    private static MarketQuote quote(String close, String previousClose) {
        return new MarketQuote(47, DAY, close == null ? null : new BigDecimal(close),
                previousClose == null ? null : new BigDecimal(previousClose));
    }
}
