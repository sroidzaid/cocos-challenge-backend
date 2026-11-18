package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSizingTest {

    @Test
    void quantityIsUsedAsIs() {
        assertThat(new OrderSizing.Quantity(10).sharesAt(new BigDecimal("925.85"))).isEqualTo(10);
    }

    @Test
    void amountIsConvertedRoundingDown() {
        // 10,000 / 925.85 = 10.80... -> 10 (never rounded up).
        assertThat(new OrderSizing.Amount(new BigDecimal("10000")).sharesAt(new BigDecimal("925.85"))).isEqualTo(10);
        assertThat(new OrderSizing.Amount(new BigDecimal("999.99")).sharesAt(new BigDecimal("100.00"))).isEqualTo(9);
        assertThat(new OrderSizing.Amount(new BigDecimal("1000.00")).sharesAt(new BigDecimal("100.00"))).isEqualTo(10);
    }

    @Test
    void amountBelowOneShareGivesZero() {
        assertThat(new OrderSizing.Amount(new BigDecimal("925.84")).sharesAt(new BigDecimal("925.85"))).isZero();
    }

    @Test
    void hugeQuantitiesAreReportedNotTruncatedToInt() {
        assertThat(new OrderSizing.Amount(new BigDecimal("9999999999.99")).sharesAt(new BigDecimal("0.01")))
                .isEqualTo(999_999_999_999L);
    }

    @Test
    void nonPositiveValuesAreRejected() {
        assertThatThrownBy(() -> new OrderSizing.Quantity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSizing.Quantity(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSizing.Amount(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderSizing.Amount(new BigDecimal("-5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyNewOrdersAreCancellable() {
        assertThat(OrderStatus.NEW.isCancellable()).isTrue();
        assertThat(OrderStatus.FILLED.isCancellable()).isFalse();
        assertThat(OrderStatus.REJECTED.isCancellable()).isFalse();
        assertThat(OrderStatus.CANCELLED.isCancellable()).isFalse();
    }

    @Test
    void newTradesRequireBuyOrSellWithPositiveSizeAndPrice() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        assertThat(Order.newTrade(1, 47, OrderSide.BUY, OrderType.LIMIT, OrderStatus.NEW, 1, BigDecimal.ONE, now).id())
                .isNull();
        assertThatThrownBy(() -> Order.newTrade(1, 66, OrderSide.CASH_IN, OrderType.MARKET, OrderStatus.FILLED, 1,
                BigDecimal.ONE, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.newTrade(1, 47, OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED, 0,
                BigDecimal.ONE, now)).isInstanceOf(IllegalArgumentException.class);
    }
}
