package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Valuation of positions and account totals, with the figures of user 1 in the seed. */
class PortfolioTest {

    private static final PositionValuation PAMP = PositionValuation.of(
            new Holding(47, 40, new BigDecimal("930")), "PAMP", "Pampa Holding S.A.", new BigDecimal("925.85"));
    private static final PositionValuation METR = PositionValuation.of(
            new Holding(54, 500, new BigDecimal("250")), "METR", "MetroGAS S.A.", new BigDecimal("229.50"));
    private static final PositionValuation BMA = PositionValuation.of(
            new Holding(31, -10, null), "BMA", "Banco Macro S.A.", new BigDecimal("1502.80"));

    @Test
    void pricedLongPositionHasMarketValueAndReturn() {
        assertThat(PAMP.status()).isEqualTo(ValuationStatus.OK);
        assertThat(PAMP.marketValue()).isEqualByComparingTo("37034.00");
        // -166 / 37200 = -0.4462...% -> -0.45 (HALF_EVEN, 2 decimals).
        assertThat(PAMP.totalReturnPercent()).isEqualByComparingTo("-0.45");
        assertThat(METR.totalReturnPercent()).isEqualByComparingTo("-8.20");
    }

    @Test
    void negativePositionIsValuedWithoutCostNorReturn() {
        assertThat(BMA.status()).isEqualTo(ValuationStatus.NEGATIVE_QUANTITY);
        assertThat(BMA.marketValue()).isEqualByComparingTo("-15028.00");
        assertThat(BMA.averageCost()).isNull();
        assertThat(BMA.totalReturnPercent()).isNull();
    }

    @Test
    void positionWithoutPriceIsNotValued() {
        PositionValuation unpriced = PositionValuation.of(new Holding(3, 10, new BigDecimal("50")), "PGR", "Phoenix",
                null);

        assertThat(unpriced.status()).isEqualTo(ValuationStatus.NO_MARKET_PRICE);
        assertThat(unpriced.marketValue()).isNull();
        assertThat(unpriced.totalReturnPercent()).isNull();
        assertThat(unpriced.averageCost()).isEqualByComparingTo("50");
    }

    @Test
    void totalIncludesCashAndEveryPricedPositionIncludingNegativeOnes() {
        Portfolio portfolio = Portfolio.of(1, new BigDecimal("753000.00"), new BigDecimal("125500.00"),
                List.of(PAMP, METR, BMA));

        assertThat(portfolio.cashBalance()).isEqualByComparingTo("753000.00");
        assertThat(portfolio.availableCash()).isEqualByComparingTo("627500.00");
        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("889756.00");
        assertThat(portfolio.valuationComplete()).isTrue();
        assertThat(portfolio.positions()).extracting(PositionValuation::ticker).containsExactly("BMA", "METR", "PAMP");
        assertThat(portfolio.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(ValuationStatus.NEGATIVE_QUANTITY);
            assertThat(w.instrumentId()).isEqualTo(31);
        });
    }

    @Test
    void unpricedPositionMakesTheTotalPartial() {
        PositionValuation unpriced = PositionValuation.of(new Holding(3, 10, new BigDecimal("50")), "PGR", "Phoenix",
                null);

        Portfolio portfolio = Portfolio.of(1, new BigDecimal("1000.00"), BigDecimal.ZERO, List.of(PAMP, unpriced));

        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("38034.00");
        assertThat(portfolio.valuationComplete()).isFalse();
        assertThat(portfolio.warnings()).singleElement()
                .extracting(PortfolioWarning::code).isEqualTo(ValuationStatus.NO_MARKET_PRICE);
    }

    @Test
    void negativeUnpricedPositionReportsBothWarnings() {
        PositionValuation negativeUnpriced = PositionValuation.of(new Holding(3, -5, null), "PGR", "Phoenix", null);

        Portfolio portfolio = Portfolio.of(1, new BigDecimal("1000.00"), BigDecimal.ZERO, List.of(negativeUnpriced));

        assertThat(negativeUnpriced.status()).isEqualTo(ValuationStatus.NO_MARKET_PRICE);
        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("1000.00");
        assertThat(portfolio.warnings()).extracting(PortfolioWarning::code)
                .containsExactly(ValuationStatus.NO_MARKET_PRICE, ValuationStatus.NEGATIVE_QUANTITY);
    }

    @Test
    void emptyAccount() {
        Portfolio portfolio = Portfolio.of(2, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("0");
        assertThat(portfolio.valuationComplete()).isTrue();
        assertThat(portfolio.positions()).isEmpty();
        assertThat(portfolio.warnings()).isEmpty();
    }
}
