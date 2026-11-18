package com.cocos.challenge.orders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.cocos.challenge.orders.application.port.in.CancelOrderUseCase;
import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.domain.DomainException;
import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.SeedDataRestorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two operations of the same user start at the same time (barrier), each in its own thread, transaction and
 * connection, against PostgreSQL. The account lock must serialize them so that the second one sees what the first
 * committed: the same cash or the same shares can never be used twice. No sleeps: threads are joined with timeouts.
 */
@ApplicationIT
class OrderConcurrencyIT {

    private static final int PAMP = 47;

    @Autowired
    private PlaceOrderUseCase placeOrder;

    @Autowired
    private CancelOrderUseCase cancelOrder;

    @Autowired
    private LedgerQuery ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        // User 2 has no orders in the seed: fund it with ARS 10,000 (committed).
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime)
                VALUES (66, 2, 10000, 1, 'MARKET', 'CASH_IN', 'FILLED', '2023-07-14 09:00:00')""");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        seed.restoreOrders();
    }

    @Test
    void twoBuyMarketOrdersCannotSpendTheSameCash() throws Exception {
        // Each costs 6 x 925.85 = 5,555.10; together 11,110.20 > 10,000.
        List<String> outcomes = concurrently(
                () -> place(2, OrderSide.BUY, OrderType.MARKET, 6, null),
                () -> place(2, OrderSide.BUY, OrderType.MARKET, 6, null));

        assertThat(outcomes).containsExactlyInAnyOrder("FILLED", "REJECTED");
        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo("4444.90");
        assertAvailabilityNeverNegative(2);
    }

    @Test
    void twoBuyLimitOrdersCannotReserveTheSameCash() throws Exception {
        List<String> outcomes = concurrently(
                () -> place(2, OrderSide.BUY, OrderType.LIMIT, 6, new BigDecimal("925.85")),
                () -> place(2, OrderSide.BUY, OrderType.LIMIT, 6, new BigDecimal("925.85")));

        assertThat(outcomes).containsExactlyInAnyOrder("NEW", "REJECTED");
        assertThat(ledger.reservations(2).cash()).isEqualByComparingTo("5555.10");
        assertThat(ledger.cashBalance(2).amount()).as("reservations do not move cash").isEqualByComparingTo("10000");
        assertAvailabilityNeverNegative(2);
    }

    @Test
    void twoSellMarketOrdersCannotSellTheSameShares() throws Exception {
        // User 1 holds 40 PAMP; 30 + 30 > 40.
        List<String> outcomes = concurrently(
                () -> place(1, OrderSide.SELL, OrderType.MARKET, 30, null),
                () -> place(1, OrderSide.SELL, OrderType.MARKET, 30, null));

        assertThat(outcomes).containsExactlyInAnyOrder("FILLED", "REJECTED");
        assertThat(pampQuantity(1)).isEqualTo(10);
        assertAvailabilityNeverNegative(1);
    }

    @Test
    void twoSellLimitOrdersCannotReserveTheSameShares() throws Exception {
        List<String> outcomes = concurrently(
                () -> place(1, OrderSide.SELL, OrderType.LIMIT, 30, new BigDecimal("1000.00")),
                () -> place(1, OrderSide.SELL, OrderType.LIMIT, 30, new BigDecimal("1000.00")));

        assertThat(outcomes).containsExactlyInAnyOrder("NEW", "REJECTED");
        assertThat(ledger.reservations(1).sharesOf(PAMP)).isEqualTo(30);
        assertThat(pampQuantity(1)).as("NEW sells do not change holdings").isEqualTo(40);
        assertAvailabilityNeverNegative(1);
    }

    @Test
    void twoCashOutsCannotWithdrawTheSameCash() throws Exception {
        // 6,000 + 6,000 > 10,000.
        List<String> outcomes = concurrently(
                () -> transfer(2, OrderSide.CASH_OUT, 6000),
                () -> transfer(2, OrderSide.CASH_OUT, 6000));

        assertThat(outcomes).containsExactlyInAnyOrder("FILLED", "REJECTED");
        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo("4000");
        assertAvailabilityNeverNegative(2);
    }

    @Test
    void cashOutAndBuyCannotSpendTheSameCash() throws Exception {
        // CASH_OUT 6,000 and BUY 6 x 925.85 = 5,555.10; together 11,555.10 > 10,000.
        List<String> outcomes = concurrently(
                () -> transfer(2, OrderSide.CASH_OUT, 6000),
                () -> place(2, OrderSide.BUY, OrderType.MARKET, 6, null));

        assertThat(outcomes).containsExactlyInAnyOrder("FILLED", "REJECTED");
        boolean cashOutWon = outcomes.getFirst().equals("FILLED");
        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo(cashOutWon ? "4000" : "4444.90");
        assertAvailabilityNeverNegative(2);
    }

    @Test
    void cashOutCannotWithdrawCashReservedByAConcurrentBuyLimit() throws Exception {
        List<String> outcomes = concurrently(
                () -> transfer(2, OrderSide.CASH_OUT, 6000),
                () -> place(2, OrderSide.BUY, OrderType.LIMIT, 6, new BigDecimal("925.85")));

        assertThat(outcomes).isIn(List.of("FILLED", "REJECTED"), List.of("REJECTED", "NEW"));
        boolean cashOutWon = outcomes.getFirst().equals("FILLED");
        assertThat(ledger.cashBalance(2).amount()).isEqualByComparingTo(cashOutWon ? "4000" : "10000");
        assertThat(ledger.reservations(2).cash()).isEqualByComparingTo(cashOutWon ? "0" : "5555.10");
        assertAvailabilityNeverNegative(2);
    }

    @Test
    void aNewOrderCanBeCancelledOnlyOnce() throws Exception {
        List<String> outcomes = concurrently(() -> cancel(1, 5), () -> cancel(1, 5));

        assertThat(outcomes).containsExactlyInAnyOrder("CANCELLED", "ORDER_NOT_CANCELLABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 5", String.class)).isEqualTo("CANCELLED");
        assertThat(ledger.reservations(1).cash()).as("only order 7 still reserves: 60 x 1,500")
                .isEqualByComparingTo("90000.00");
    }

    private String place(int userId, OrderSide side, OrderType type, int quantity, BigDecimal limitPrice) {
        return placeOrder.place(new PlaceOrderCommand(userId, PAMP, side, type, new OrderSizing.Quantity(quantity),
                limitPrice, UUID.randomUUID())).order().status().name();
    }

    private String transfer(int userId, OrderSide side, int amount) {
        return placeOrder.place(new PlaceOrderCommand(userId, null, side, null,
                new OrderSizing.Amount(BigDecimal.valueOf(amount)), null, UUID.randomUUID())).order().status().name();
    }

    private String cancel(int userId, int orderId) {
        try {
            OrderStatus status = cancelOrder.cancel(userId, orderId).status();
            return status.name();
        } catch (DomainException e) {
            return e.getCode();
        }
    }

    private List<String> concurrently(Callable<String> first, Callable<String> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<String> a = executor.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return first.call();
        });
        Future<String> b = executor.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return second.call();
        });
        return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }

    private long pampQuantity(int userId) {
        return ledger.netPositions(userId).stream()
                .filter(p -> p.instrumentId() == PAMP)
                .mapToLong(NetPosition::quantity)
                .sum();
    }

    private void assertAvailabilityNeverNegative(int userId) {
        assertThat(ledger.cashBalance(userId).amount().subtract(ledger.reservations(userId).cash()))
                .as("available cash").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(pampQuantity(userId) - ledger.reservations(userId).sharesOf(PAMP))
                .as("available PAMP").isGreaterThanOrEqualTo(0L);
    }
}
