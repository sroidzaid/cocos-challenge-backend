package com.cocos.challenge.support;

import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restores the {@code orders} table to the seed of database.sql, for tests that need committed data (HTTP calls on
 * other threads, concurrency tests) and therefore cannot rely on rollback.
 * <p>
 * The seed rows are captured in memory when the test context starts. Restoring truncates the table, re-inserts the
 * seed rows with their original ids and resets the id sequence. Unlike deleting rows above the seed's max id, this
 * also undoes updates to seed rows (e.g. a cancelled order) and leaves the sequence as after database.sql.
 * It creates no database objects. The application only writes {@code orders} and {@code order_idempotency}
 * (empty after database.sql + V2), so both are restored.
 */
@TestComponent
public class SeedDataRestorer {

    private static final String COLUMNS = "id, instrumentid, userid, size, price, type, side, status, datetime";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private List<Map<String, Object>> seedOrders;

    SeedDataRestorer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void captureSeed() {
        seedOrders = snapshotOrders();
    }

    public List<Map<String, Object>> seedOrders() {
        return seedOrders;
    }

    public List<Map<String, Object>> snapshotOrders() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM orders ORDER BY id");
    }

    public void restoreOrders() {
        transaction.executeWithoutResult(status -> {
            // order_idempotency references orders and is empty in the seed: both are reset together.
            jdbc.execute("TRUNCATE order_idempotency, orders RESTART IDENTITY");
            jdbc.batchUpdate("INSERT INTO orders (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    seedOrders.stream()
                            .map(row -> new Object[] {row.get("id"), row.get("instrumentid"), row.get("userid"),
                                    row.get("size"), row.get("price"), row.get("type"), row.get("side"),
                                    row.get("status"), row.get("datetime")})
                            .toList());
            jdbc.queryForObject("""
                    SELECT setval(pg_get_serial_sequence('orders', 'id'), COALESCE(MAX(id), 1), MAX(id) IS NOT NULL)
                    FROM orders""", Long.class);
        });
    }
}
