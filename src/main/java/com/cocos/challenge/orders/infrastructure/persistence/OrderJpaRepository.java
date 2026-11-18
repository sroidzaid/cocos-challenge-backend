package com.cocos.challenge.orders.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderJpaRepository extends JpaRepository<OrderEntity, Integer> {

    Optional<OrderEntity> findByIdAndUserId(Integer id, Integer userId);

    List<OrderEntity> findByUserIdOrderByDatetimeAscIdAsc(Integer userId);

    List<OrderEntity> findByUserIdAndStatusInOrderByDatetimeAscIdAsc(Integer userId, Collection<String> statuses);

    /**
     * Conditional transition NEW -&gt; CANCELLED. The {@code status = 'NEW'} predicate makes it safe even against a
     * writer that does not take the account lock: PostgreSQL re-evaluates it on the locked row.
     * Clears the persistence context so no stale entity survives the bulk update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE orders SET status = 'CANCELLED'
            WHERE id = :orderId AND userid = :userId AND status = 'NEW'""", nativeQuery = true)
    int cancelIfNew(@Param("orderId") int orderId, @Param("userId") int userId);

    /**
     * Cash from FILLED orders: CASH_IN and SELL add size * price, CASH_OUT and BUY subtract it (exact NUMERIC).
     * {@code anomalies} counts FILLED rows that cannot be interpreted (unknown/null side, null size or price),
     * which would otherwise be silently ignored by SUM.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE
                                    WHEN side IN ('CASH_IN', 'SELL') THEN size * price
                                    WHEN side IN ('CASH_OUT', 'BUY') THEN -(size * price)
                                END), 0) AS "cash",
                   COUNT(*) FILTER (WHERE side IS NULL
                                       OR side NOT IN ('CASH_IN', 'CASH_OUT', 'BUY', 'SELL')
                                       OR size IS NULL
                                       OR price IS NULL) AS "anomalies"
            FROM orders
            WHERE userid = :userId AND status = 'FILLED'""", nativeQuery = true)
    CashAggregate cashAggregate(@Param("userId") int userId);

    /**
     * Net quantity per instrument from FILLED BUY/SELL orders, as persisted (zero and negative included).
     * {@code anomalies} counts rows of the group without size, which SUM would silently ignore.
     */
    @Query(value = """
            SELECT instrumentid AS "instrumentId",
                   SUM(CASE side WHEN 'BUY' THEN size ELSE -size END) AS "netQuantity",
                   COUNT(*) FILTER (WHERE size IS NULL) AS "anomalies"
            FROM orders
            WHERE userid = :userId AND status = 'FILLED' AND side IN ('BUY', 'SELL')
            GROUP BY instrumentid
            ORDER BY instrumentid""", nativeQuery = true)
    List<NetPositionRow> netPositions(@Param("userId") int userId);

    /**
     * Cash reserved by pending orders: sum of size * price of NEW BUY orders (exact NUMERIC).
     * {@code anomalies} counts NEW rows that cannot be interpreted as a reservation (a side other than BUY/SELL,
     * missing size, price or instrument): only BUY/SELL orders can stay NEW in this application.
     */
    @Query(value = """
            SELECT COALESCE(SUM(size * price) FILTER (WHERE side = 'BUY'), 0) AS "cash",
                   COUNT(*) FILTER (WHERE side IS NULL
                                       OR side NOT IN ('BUY', 'SELL')
                                       OR size IS NULL
                                       OR price IS NULL
                                       OR instrumentid IS NULL) AS "anomalies"
            FROM orders
            WHERE userid = :userId AND status = 'NEW'""", nativeQuery = true)
    CashAggregate reservedCashAggregate(@Param("userId") int userId);

    /** Shares reserved by NEW SELL orders, per instrument (rows with null size/instrument are counted above). */
    @Query(value = """
            SELECT instrumentid AS "instrumentId",
                   SUM(size) AS "shares"
            FROM orders
            WHERE userid = :userId AND status = 'NEW' AND side = 'SELL'
                  AND instrumentid IS NOT NULL AND size IS NOT NULL
            GROUP BY instrumentid
            ORDER BY instrumentid""", nativeQuery = true)
    List<ReservedSharesRow> reservedShares(@Param("userId") int userId);

    interface ReservedSharesRow {
        Integer getInstrumentId();

        Long getShares();
    }

    interface CashAggregate {
        BigDecimal getCash();

        Long getAnomalies();
    }

    interface NetPositionRow {
        Integer getInstrumentId();

        Long getNetQuantity();

        Long getAnomalies();
    }
}
