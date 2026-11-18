package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.domain.MonetaryPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionCalculatorTest {

    private static final int PAMP = 47;
    private static final int METR = 54;
    private static final LocalDateTime T0 = LocalDateTime.of(2023, 7, 12, 10, 0);

    private final List<Order> orders = new ArrayList<>();

    @Test
    void successiveBuysUseTheWeightedAverage() {
        buy(PAMP, 100, "10");
        buy(PAMP, 100, "20");

        assertThat(single()).satisfies(h -> {
            assertThat(h.quantity()).isEqualTo(200);
            assertThat(h.averageCost()).isEqualByComparingTo("15");
        });
    }

    @Test
    void averageIsKeptUnroundedInternally() {
        buy(PAMP, 1, "10");
        buy(PAMP, 2, "10.01");

        // (10 + 20.02) / 3 = 10.00666..., only rounded for presentation.
        assertThat(single().averageCost())
                .isEqualByComparingTo(new BigDecimal("30.02").divide(BigDecimal.valueOf(3), MonetaryPolicy.MATH));
        assertThat(MonetaryPolicy.toMoneyScale(single().averageCost())).isEqualByComparingTo("10.01");
    }

    @Test
    void partialSaleKeepsTheAverage() {
        buy(PAMP, 50, "930");
        sell(PAMP, 10, "940");

        assertThat(single()).isEqualTo(new Holding(PAMP, 40, new BigDecimal("930")));
    }

    @Test
    void closingThePositionResetsTheAverageForTheNextBuy() {
        buy(PAMP, 10, "100");
        sell(PAMP, 10, "150");
        buy(PAMP, 5, "200");

        assertThat(single()).isEqualTo(new Holding(PAMP, 5, new BigDecimal("200")));
    }

    @Test
    void closedPositionsAreNotListed() {
        buy(PAMP, 10, "100");
        sell(PAMP, 10, "150");

        assertThat(PositionCalculator.holdings(orders)).isEmpty();
    }

    @Test
    void oversoldPositionIsNegativeWithoutAverage() {
        buy(31, 20, "1540");
        sell(31, 30, "1530");

        assertThat(single()).isEqualTo(new Holding(31, -10, null));
    }

    @Test
    void buyThatDoesNotCrossZeroKeepsTheAverageUndefined() {
        sell(PAMP, 10, "100");
        buy(PAMP, 4, "90");

        assertThat(single()).isEqualTo(new Holding(PAMP, -6, null));
    }

    @Test
    void buyThatCrossesFromNegativeToPositiveStartsAtItsPrice() {
        sell(PAMP, 10, "100");
        buy(PAMP, 15, "90");

        assertThat(single()).isEqualTo(new Holding(PAMP, 5, new BigDecimal("90")));
    }

    @Test
    void ordersAreReplayedChronologicallyWithTheIdAsTieBreaker() {
        // Same datetime: id decides. Given out of order on purpose.
        orders.add(order(3, PAMP, OrderSide.BUY, 10, "300", T0.plusHours(1)));
        orders.add(order(2, PAMP, OrderSide.SELL, 10, "200", T0));
        orders.add(order(1, PAMP, OrderSide.BUY, 10, "100", T0));

        // 1: buy 10@100 -> 2: sell 10 (flat, reset) -> 3: buy 10@300.
        assertThat(single()).isEqualTo(new Holding(PAMP, 10, new BigDecimal("300")));
    }

    @Test
    void onlyFilledTradesCount() {
        buy(PAMP, 10, "100");
        orders.add(new Order(100, 1, 66, OrderSide.CASH_IN, OrderType.MARKET, OrderStatus.FILLED, 1000,
                BigDecimal.ONE, T0));
        orders.add(new Order(101, 1, PAMP, OrderSide.BUY, OrderType.LIMIT, OrderStatus.NEW, 10, new BigDecimal("1"),
                T0));
        orders.add(new Order(102, 1, PAMP, OrderSide.SELL, OrderType.MARKET, OrderStatus.REJECTED, 99,
                new BigDecimal("1"), T0));
        orders.add(new Order(103, 1, PAMP, OrderSide.BUY, OrderType.LIMIT, OrderStatus.CANCELLED, 10,
                new BigDecimal("1"), T0));

        assertThat(PositionCalculator.holdings(orders)).containsExactly(new Holding(PAMP, 10, new BigDecimal("100")));
    }

    @Test
    void positionsAreIndependentPerInstrumentAndOrderedById() {
        buy(METR, 500, "250");
        buy(PAMP, 50, "930");

        assertThat(PositionCalculator.holdings(orders)).extracting(Holding::instrumentId).containsExactly(PAMP, METR);
    }

    @Test
    void invalidStoredPriceFailsInsteadOfProducingACost() {
        orders.add(new Order(1, 1, PAMP, OrderSide.BUY, OrderType.MARKET, OrderStatus.FILLED, 10, null, T0));

        assertThatThrownBy(() -> PositionCalculator.holdings(orders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id=1");
    }

    @Test
    void zeroPriceFails() {
        buy(PAMP, 10, "0");

        assertThatThrownBy(() -> PositionCalculator.holdings(orders)).isInstanceOf(IllegalStateException.class);
    }

    private Holding single() {
        List<Holding> holdings = PositionCalculator.holdings(orders);
        assertThat(holdings).hasSize(1);
        return holdings.getFirst();
    }

    private void buy(int instrumentId, int size, String price) {
        add(instrumentId, OrderSide.BUY, size, price);
    }

    private void sell(int instrumentId, int size, String price) {
        add(instrumentId, OrderSide.SELL, size, price);
    }

    private void add(int instrumentId, OrderSide side, int size, String price) {
        int id = orders.size() + 1;
        orders.add(order(id, instrumentId, side, size, price, T0.plusMinutes(id)));
    }

    private static Order order(int id, int instrumentId, OrderSide side, int size, String price, LocalDateTime at) {
        return new Order(id, 1, instrumentId, side, OrderType.MARKET, OrderStatus.FILLED, size, new BigDecimal(price),
                at);
    }
}
