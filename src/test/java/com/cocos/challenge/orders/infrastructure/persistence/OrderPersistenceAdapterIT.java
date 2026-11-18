package com.cocos.challenge.orders.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import com.cocos.challenge.support.PersistenceIT;
import com.cocos.challenge.support.PreV3Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs inside a rolled-back transaction (see {@link PersistenceIT}): written rows never persist. */
@PersistenceIT
@Import(OrderPersistenceAdapter.class)
class OrderPersistenceAdapterIT {

    private static final LocalDateTime NOW = LocalDateTime.of(2023, 7, 14, 10, 0);
    private static final String INSERT_COLUMNS = "instrumentid, userid, size, price, type, side, status, datetime";

    @Autowired
    private OrderPersistenceAdapter adapter;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void readsAllOrdersOfAUserInChronologicalOrder() {
        List<Order> orders = adapter.findByUserId(1);

        assertThat(orders).hasSize(11);
        assertThat(orders).extracting(Order::id).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(orders.getFirst()).isEqualTo(new Order(1, 1, 66, OrderSide.CASH_IN, OrderType.MARKET,
                OrderStatus.FILLED, 1_000_000, new BigDecimal("1.00"), LocalDateTime.of(2023, 7, 12, 12, 11, 20)));
        assertThat(adapter.findByUserId(2)).isEmpty();
    }

    @Test
    void filtersByStatus() {
        assertThat(adapter.findByUserIdAndStatusIn(1, Set.of(OrderStatus.FILLED)))
                .extracting(Order::id).containsExactly(1, 2, 4, 8, 9, 10, 11);
        assertThat(adapter.findByUserIdAndStatusIn(1, Set.of(OrderStatus.NEW)))
                .extracting(Order::id).containsExactly(5, 7);
        assertThat(adapter.findByUserIdAndStatusIn(1, Set.of(OrderStatus.CANCELLED, OrderStatus.REJECTED)))
                .extracting(Order::id).containsExactly(3, 6);
        assertThat(adapter.findByUserIdAndStatusIn(1, Set.of())).isEmpty();
    }

    @Test
    void findsASingleOrderOnlyForItsOwner() {
        assertThat(adapter.findByIdAndUserId(5, 1)).map(Order::status).contains(OrderStatus.NEW);
        assertThat(adapter.findByIdAndUserId(5, 2)).isEmpty();
        assertThat(adapter.findByIdAndUserId(999, 1)).isEmpty();
    }

    @Test
    void persistsAndReloadsAnOrderWithExactPrice() {
        Order saved = adapter.save(new Order(null, 2, 47, OrderSide.BUY, OrderType.LIMIT, OrderStatus.NEW, 3,
                new BigDecimal("900.50"), NOW));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.id()).isNotNull();
        assertThat(adapter.findByIdAndUserId(saved.id(), 2)).hasValueSatisfying(reloaded -> {
            assertThat(reloaded).usingRecursiveComparison().ignoringFields("price").isEqualTo(saved);
            assertThat(reloaded.price()).isEqualByComparingTo("900.50");
        });
    }

    @Test
    void cancelIfNewOnlyAffectsNewOrdersOfTheOwner() {
        assertThat(adapter.cancelIfNew(5, 2)).as("another user's order").isFalse();
        assertThat(adapter.cancelIfNew(1, 1)).as("FILLED order").isFalse();
        assertThat(adapter.cancelIfNew(999, 1)).as("missing order").isFalse();

        assertThat(adapter.cancelIfNew(5, 1)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 5", String.class)).isEqualTo("CANCELLED");
        assertThat(adapter.cancelIfNew(5, 1)).as("already cancelled").isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 1", String.class)).isEqualTo("FILLED");
    }

    /** V3: the database itself rejects rows that would corrupt balances, even when written outside the API. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            null instrumentid  | NULL, 1, 1, 10, 'MARKET', 'BUY', 'FILLED', now()
            null userid        | 47, NULL, 1, 10, 'MARKET', 'BUY', 'FILLED', now()
            null size          | 47, 1, NULL, 10, 'MARKET', 'BUY', 'FILLED', now()
            null type          | 47, 1, 1, 10, NULL, 'BUY', 'FILLED', now()
            null side          | 47, 1, 1, 10, 'MARKET', NULL, 'FILLED', now()
            null status        | 47, 1, 1, 10, 'MARKET', 'BUY', NULL, now()
            null datetime      | 47, 1, 1, 10, 'MARKET', 'BUY', 'FILLED', NULL
            unknown side       | 66, 1, 100, 1, 'MARKET', 'DEPOSIT', 'FILLED', now()
            unknown type       | 47, 1, 1, 10, 'STOP', 'BUY', 'FILLED', now()
            unknown status     | 47, 1, 1, 10, 'MARKET', 'BUY', 'PARTIAL', now()
            zero size          | 47, 1, 0, 10, 'MARKET', 'BUY', 'FILLED', now()
            negative size      | 47, 1, -5, 10, 'MARKET', 'BUY', 'FILLED', now()
            zero price         | 47, 1, 1, 0, 'MARKET', 'BUY', 'FILLED', now()
            negative price     | 47, 1, 1, -10, 'MARKET', 'BUY', 'FILLED', now()
            """)
    void databaseRejectsOrdersThatViolateV3(String rule, String values) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO orders (" + INSERT_COLUMNS + ") VALUES (" + values + ")"))
                .as(rule)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v3AcceptsEveryKindOfOrderTheApplicationWrites() {
        // BUY/SELL MARKET and LIMIT, REJECTED with real size/price, CASH_IN/CASH_OUT (ARS, size = amount, price = 1),
        // and a NULL price (kept nullable on purpose).
        int inserted = jdbc.update("INSERT INTO orders (" + INSERT_COLUMNS + ") VALUES "
                + "(47, 2, 10, 925.85, 'MARKET', 'BUY', 'FILLED', now()),"
                + "(47, 2, 5, 1000.00, 'LIMIT', 'SELL', 'NEW', now()),"
                + "(47, 2, 1000, 925.85, 'MARKET', 'BUY', 'REJECTED', now()),"
                + "(47, 2, 5, 1000.00, 'LIMIT', 'SELL', 'CANCELLED', now()),"
                + "(66, 2, 10000, 1, 'MARKET', 'CASH_IN', 'FILLED', now()),"
                + "(66, 2, 500, 1, 'MARKET', 'CASH_OUT', 'FILLED', now()),"
                + "(47, 2, 1, NULL, 'LIMIT', 'BUY', 'CANCELLED', now())");

        assertThat(inserted).isEqualTo(7);
    }

    @Test
    void foreignKeysAreEnforcedByTheDatabase() {
        Integer orphans = jdbc.queryForObject("""
                SELECT count(*) FROM orders o
                LEFT JOIN instruments i ON i.id = o.instrumentid
                LEFT JOIN users u ON u.id = o.userid
                WHERE i.id IS NULL OR u.id IS NULL""", Integer.class);
        assertThat(orphans).isZero();

        assertThatThrownBy(() -> adapter.save(new Order(null, 1, 9999, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.NEW, 1, new BigDecimal("10.00"), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void priceWithMoreThanTwoDecimalsIsRejectedInsteadOfRoundedByPostgres() {
        assertThatThrownBy(() -> adapter.save(new Order(null, 1, 47, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.NEW, 1, new BigDecimal("900.505"), NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no silent rounding");
    }

    @Test
    void priceOutOfNumericRangeIsRejected() {
        assertThatThrownBy(() -> adapter.save(new Order(null, 1, 47, OrderSide.BUY, OrderType.LIMIT,
                OrderStatus.NEW, 1, new BigDecimal("100000000.00"), NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedStoredValueFailsExplicitlyInsteadOfBeingSkipped() {
        // V3 rejects this value; the application defense still matters for databases without V3.
        PreV3Schema.dropCheck(jdbc, "chk_orders_status");
        jdbc.update("UPDATE orders SET status = 'PARTIAL' WHERE id = 3");

        assertThatThrownBy(() -> adapter.findByUserId(1))
                .isInstanceOf(PersistedDataIntegrityException.class)
                .hasMessageContaining("orders.id=3");
    }
}
