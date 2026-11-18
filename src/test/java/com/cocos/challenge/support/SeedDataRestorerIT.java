package com.cocos.challenge.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the restoration strategy used by tests that commit data. */
@ApplicationIT
class SeedDataRestorerIT {

    @Autowired
    private SeedDataRestorer restorer;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        restorer.restoreOrders();
    }

    @Test
    void restoresInsertedAndUpdatedRowsAndTheIdSequence() {
        assertThat(restorer.seedOrders()).hasSize(11);

        // Committed changes (auto-commit, no surrounding transaction): an insert and an update of a seed row.
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime)
                VALUES (47, 2, 1, 900, 'LIMIT', 'BUY', 'NEW', now())""");
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = 5");
        assertThat(restorer.snapshotOrders()).isNotEqualTo(restorer.seedOrders());

        restorer.restoreOrders();

        assertThat(restorer.snapshotOrders()).isEqualTo(restorer.seedOrders());
        assertThat(jdbc.queryForObject("SELECT last_value FROM orders_id_seq", Long.class)).isEqualTo(11L);
        assertThat(jdbc.queryForObject("SELECT is_called FROM orders_id_seq", Boolean.class)).isTrue();
    }
}
