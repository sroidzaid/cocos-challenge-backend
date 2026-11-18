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

import com.cocos.challenge.orders.application.port.in.PlaceOrderCommand;
import com.cocos.challenge.orders.application.port.in.PlaceOrderUseCase;
import com.cocos.challenge.orders.application.port.in.PlacedOrder;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderSizing;
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
 * Two requests with the same idempotency key start at the same time (barrier), each in its own thread, transaction
 * and connection. The account lock serializes them and the second one, after the lock, sees the key committed by the
 * first: at most one order exists for the intention. No sleeps: threads are joined with timeouts.
 */
@ApplicationIT
class OrderIdempotencyConcurrencyIT {

    private static final int SEED_ORDERS = 11;

    @Autowired
    private PlaceOrderUseCase placeOrder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        seed.restoreOrders();
    }

    @Test
    void concurrentRetriesWithTheSameKeyPlaceASingleOrder() throws Exception {
        UUID key = UUID.randomUUID();

        List<Object> outcomes = concurrently(() -> placeOrder.place(buyLimit(10, key)),
                () -> placeOrder.place(buyLimit(10, key)));

        PlacedOrder a = (PlacedOrder) outcomes.get(0);
        PlacedOrder b = (PlacedOrder) outcomes.get(1);
        assertThat(a.order().id()).as("both get the same order").isEqualTo(b.order().id());
        assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(false, true);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_idempotency WHERE idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'NEW' AND userid = 1",
                Integer.class)).as("seed NEW orders 5 and 7 plus one").isEqualTo(3);
    }

    @Test
    void concurrentRequestsWithTheSameKeyAndDifferentPayloadsPlaceOneAndRejectTheOther() throws Exception {
        UUID key = UUID.randomUUID();

        List<Object> outcomes = concurrently(() -> placeOrder.place(buyLimit(10, key)),
                () -> placeOrder.place(buyLimit(20, key)));

        assertThat(outcomes).filteredOn(PlacedOrder.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(String.class::isInstance).containsExactly("IDEMPOTENCY_KEY_REUSED");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void concurrentRequestsWithDifferentKeysAreIndependentOrders() throws Exception {
        List<Object> outcomes = concurrently(() -> placeOrder.place(buyLimit(10, UUID.randomUUID())),
                () -> placeOrder.place(buyLimit(10, UUID.randomUUID())));

        assertThat(outcomes).allMatch(PlacedOrder.class::isInstance);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 2);
    }

    private static PlaceOrderCommand buyLimit(int quantity, UUID key) {
        return new PlaceOrderCommand(1, 47, OrderSide.BUY, OrderType.LIMIT, new OrderSizing.Quantity(quantity),
                new BigDecimal("900.00"), key);
    }

    /** Each outcome is the result, or the error code of a business exception. */
    private List<Object> concurrently(Callable<Object> first, Callable<Object> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Object> a = executor.submit(() -> run(start, first));
        Future<Object> b = executor.submit(() -> run(start, second));
        return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }

    private static Object run(CyclicBarrier start, Callable<Object> action) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            return action.call();
        } catch (DomainException e) {
            return e.getCode();
        }
    }

    private Integer orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }
}
