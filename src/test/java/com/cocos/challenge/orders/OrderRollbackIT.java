package com.cocos.challenge.orders;

import java.math.BigDecimal;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.out.OrderIdempotencyPort;
import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.SeedDataRestorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * A technical failure after rows were written rolls the whole operation back: lock, validation, the order insert and
 * the idempotency insert share one transaction, so neither a half-done order nor a residual idempotency key survives,
 * and the same key can be used again.
 */
@ApplicationIT
class OrderRollbackIT {

    private static final String BUY_10_PAMP = """
            {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 10}""";
    private static final String CASH_IN_5000 = """
            {"side": "CASH_IN", "amount": 5000}""";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    @MockitoSpyBean
    private OrderRepositoryPort orders;

    @MockitoSpyBean
    private OrderIdempotencyPort idempotency;

    @AfterEach
    void restoreSeed() {
        seed.restoreOrders();
    }

    @Test
    void failureAfterTheInsertLeavesNoOrderBehind() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("connection lost after insert");
        }).when(orders).save(any());

        MvcTestResult result = post(UUID.randomUUID());

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orderCount()).isEqualTo(11);
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void failureAfterBothInsertsLeavesNeitherTheOrderNorTheKeyAndTheKeyCanBeRetried() {
        UUID key = UUID.randomUUID();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("connection lost before commit");
        }).when(idempotency).save(any());

        assertThat(post(key)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orderCount()).isEqualTo(11);
        assertThat(idempotencyCount()).as("no residual idempotency record").isZero();

        doCallRealMethod().when(idempotency).save(any());
        MvcTestResult retry = post(key);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).doesNotContainHeader("Idempotent-Replayed");
        assertThat(orderCount()).isEqualTo(12);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void failedCashInCreditsNothingAndItsRetryCreditsOnce() {
        UUID key = UUID.randomUUID();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("connection lost before commit");
        }).when(idempotency).save(any());

        assertThat(post(key, CASH_IN_5000)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orderCount()).isEqualTo(11);
        assertThat(idempotencyCount()).isZero();
        assertThat(cash()).isEqualTo("753000.00");

        doCallRealMethod().when(idempotency).save(any());
        assertThat(post(key, CASH_IN_5000)).hasStatus(HttpStatus.CREATED);
        assertThat(post(key, CASH_IN_5000)).hasHeader("Idempotent-Replayed", "true");
        assertThat(orderCount()).isEqualTo(12);
        assertThat(cash()).isEqualTo("758000.00");
    }

    @Test
    void failedCashOutDebitsNothing() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("connection lost after insert");
        }).when(orders).save(any());

        assertThat(post(UUID.randomUUID(), """
                {"side": "CASH_OUT", "amount": 3000}""")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orderCount()).isEqualTo(11);
        assertThat(idempotencyCount()).isZero();
        assertThat(cash()).isEqualTo("753000.00");
    }

    private String cash() {
        return jdbc.queryForObject("""
                SELECT SUM(CASE WHEN side IN ('CASH_IN', 'SELL') THEN size * price ELSE -(size * price) END)
                FROM orders WHERE userid = 1 AND status = 'FILLED'""", BigDecimal.class).toPlainString();
    }

    private MvcTestResult post(UUID key) {
        return post(key, BUY_10_PAMP);
    }

    private MvcTestResult post(UUID key, String body) {
        return mvc.post().uri("/api/v1/users/1/orders").header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private Integer orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    private Integer idempotencyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Integer.class);
    }
}
