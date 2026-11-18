package com.cocos.challenge.orders.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPlacementPolicyTest {

    private static final BigDecimal PRICE = new BigDecimal("925.85");

    @Test
    void buyMarketWithEnoughCashIsFilled() {
        assertThat(decide(OrderSide.BUY, OrderType.MARKET, 10, cash("10000.00")))
                .isEqualTo(new PlacementDecision(OrderStatus.FILLED, null));
    }

    @Test
    void buyLimitWithEnoughCashStaysNew() {
        assertThat(decide(OrderSide.BUY, OrderType.LIMIT, 10, cash("10000.00")))
                .isEqualTo(new PlacementDecision(OrderStatus.NEW, null));
    }

    @Test
    void buyCostingExactlyTheAvailableCashIsAccepted() {
        // 10 x 925.85 = 9258.50, exact comparison without rounding.
        assertThat(decide(OrderSide.BUY, OrderType.MARKET, 10, cash("9258.50")).status())
                .isEqualTo(OrderStatus.FILLED);
        assertThat(decide(OrderSide.BUY, OrderType.MARKET, 10, cash("9258.49")))
                .isEqualTo(new PlacementDecision(OrderStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS));
    }

    @Test
    void buyWithoutEnoughCashIsRejectedForMarketAndLimit() {
        assertThat(decide(OrderSide.BUY, OrderType.MARKET, 11, cash("10000.00")).reason())
                .isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
        assertThat(decide(OrderSide.BUY, OrderType.LIMIT, 11, cash("10000.00")).reason())
                .isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void existingReservationsReduceTheAvailableCash() {
        // Seed user 1: cash 753,000 - reserved 125,500 = 627,500 available.
        Availability availability = Availability.of(new CashBalance(new BigDecimal("753000.00")),
                new Reservations(new BigDecimal("125500.00"), Map.of()), 0, 47);

        assertThat(availability.cash()).isEqualByComparingTo("627500.00");
        assertThat(OrderPlacementPolicy.decide(OrderSide.BUY, OrderType.MARKET, 678, PRICE, availability).status())
                .as("678 x 925.85 = 627,726.30 > 627,500").isEqualTo(OrderStatus.REJECTED);
        assertThat(OrderPlacementPolicy.decide(OrderSide.BUY, OrderType.MARKET, 677, PRICE, availability).status())
                .isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void sellWithEnoughSharesIsFilledOrNew() {
        assertThat(decide(OrderSide.SELL, OrderType.MARKET, 40, shares(40)).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(decide(OrderSide.SELL, OrderType.LIMIT, 40, shares(40)).status()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void sellWithoutEnoughSharesIsRejected() {
        assertThat(decide(OrderSide.SELL, OrderType.MARKET, 41, shares(40)))
                .isEqualTo(new PlacementDecision(OrderStatus.REJECTED, RejectionReason.INSUFFICIENT_HOLDINGS));
        assertThat(decide(OrderSide.SELL, OrderType.LIMIT, 41, shares(40)).reason())
                .isEqualTo(RejectionReason.INSUFFICIENT_HOLDINGS);
    }

    @Test
    void sharesReservedByNewSellOrdersAreNotAvailable() {
        Availability availability = Availability.of(new CashBalance(BigDecimal.ZERO),
                new Reservations(BigDecimal.ZERO, Map.of(47, 30L)), 40, 47);

        assertThat(availability.shares()).isEqualTo(10);
        assertThat(OrderPlacementPolicy.decide(OrderSide.SELL, OrderType.MARKET, 11, PRICE, availability).status())
                .isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void negativePositionCannotSellAnything() {
        // BMA in the seed: -10.
        assertThat(decide(OrderSide.SELL, OrderType.MARKET, 1, shares(-10)).reason())
                .isEqualTo(RejectionReason.INSUFFICIENT_HOLDINGS);
    }

    @Test
    void transfersAndInvalidValuesAreNotTrades() {
        assertThatThrownBy(() -> decide(OrderSide.CASH_IN, OrderType.MARKET, 1, cash("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decide(OrderSide.BUY, OrderType.MARKET, 0, cash("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPlacementPolicy.decide(OrderSide.BUY, OrderType.MARKET, 1, BigDecimal.ZERO,
                cash("1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cashInIsAlwaysFilled() {
        assertThat(OrderPlacementPolicy.decideTransfer(OrderSide.CASH_IN, 1_000_000, cash("-500.00")))
                .isEqualTo(new PlacementDecision(OrderStatus.FILLED, null));
    }

    @Test
    void cashOutUpToTheAvailableCashIsFilledAndAboveItIsRejected() {
        assertThat(OrderPlacementPolicy.decideTransfer(OrderSide.CASH_OUT, 1000, cash("1000.00")))
                .isEqualTo(new PlacementDecision(OrderStatus.FILLED, null));
        assertThat(OrderPlacementPolicy.decideTransfer(OrderSide.CASH_OUT, 1000, cash("999.99")))
                .isEqualTo(new PlacementDecision(OrderStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS));
        assertThat(OrderPlacementPolicy.decideTransfer(OrderSide.CASH_OUT, 1, cash("-10.00")).status())
                .isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void tradesAndInvalidAmountsAreNotTransfers() {
        assertThatThrownBy(() -> OrderPlacementPolicy.decideTransfer(OrderSide.BUY, 1, cash("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPlacementPolicy.decideTransfer(OrderSide.CASH_OUT, 0, cash("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newTransferFollowsTheConventionOfTheProvidedData() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 15);

        assertThat(Order.newTransfer(1, 66, OrderSide.CASH_OUT, OrderStatus.FILLED, 5000, at))
                .isEqualTo(new Order(null, 1, 66, OrderSide.CASH_OUT, OrderType.MARKET, OrderStatus.FILLED, 5000,
                        new BigDecimal("1.00"), at));
        assertThatThrownBy(() -> Order.newTransfer(1, 66, OrderSide.BUY, OrderStatus.FILLED, 1, at))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.newTransfer(1, 66, OrderSide.CASH_IN, OrderStatus.FILLED, 0, at))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PlacementDecision decide(OrderSide side, OrderType type, int size, Availability availability) {
        return OrderPlacementPolicy.decide(side, type, size, PRICE, availability);
    }

    private static Availability cash(String amount) {
        return new Availability(new BigDecimal(amount), 0);
    }

    private static Availability shares(long shares) {
        return new Availability(BigDecimal.ZERO, shares);
    }
}
