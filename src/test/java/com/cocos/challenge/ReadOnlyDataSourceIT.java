package com.cocos.challenge;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderSide;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.support.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the read-only protection of the "cocos" profile WITHOUT touching the Cocos database: the same datasource
 * properties used by application-cocos.yml are applied to the test container, and every write must be rejected by
 * PostgreSQL itself, both in auto-commit (JdbcTemplate) and inside a regular read-write Spring transaction (JPA).
 */
@ApplicationIT
@TestPropertySource(properties = {
        "spring.datasource.hikari.read-only=true",
        "spring.datasource.hikari.data-source-properties.readOnlyMode=always"
})
class ReadOnlyDataSourceIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepositoryPort orders;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void readsAreAllowed() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(11);
    }

    @Test
    void autoCommitWritesAreRejectedByPostgres() {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = 5"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
    }

    @Test
    void transactionalJpaWritesAreRejectedByPostgres() {
        TransactionTemplate readWrite = new TransactionTemplate(transactionManager);
        Order order = new Order(null, 2, 47, OrderSide.BUY, OrderType.LIMIT, OrderStatus.NEW, 1,
                new BigDecimal("900.00"), LocalDateTime.now());

        assertThatThrownBy(() -> readWrite.executeWithoutResult(status -> orders.save(order)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("read-only transaction");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(11);
    }
}
