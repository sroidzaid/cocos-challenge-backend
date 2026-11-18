package com.cocos.challenge.orders;

import java.util.UUID;

import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.SeedDataRestorer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency of POST /orders over HTTP against PostgreSQL. A retry with the same key and the same request returns
 * the original creation result (201, Idempotent-Replayed: true) and never places another order.
 */
@ApplicationIT
class OrderIdempotencyApiIT {

    private static final int SEED_ORDERS = 11;
    private static final String BUY_MARKET = """
            {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 10}""";
    private static final String BUY_LIMIT = """
            {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900.50}""";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    @AfterEach
    void restoreSeed() {
        seed.restoreOrders();
    }

    @Test
    void newKeyPlacesTheOrderAndStoresItsKey() {
        UUID key = UUID.randomUUID();

        MvcTestResult result = order(1, key, BUY_MARKET);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).doesNotContainHeader("Idempotent-Replayed");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertThat(jdbc.queryForObject("""
                SELECT order_id || ':' || order_status FROM order_idempotency
                WHERE user_id = 1 AND idempotency_key = ?""", String.class, key)).isEqualTo("12:FILLED");
    }

    @Test
    void retryOfAFilledMarketOrderReturnsTheSameOrderWithoutPlacingAnother() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(1, key, BUY_MARKET);

        MvcTestResult retry = order(1, key, BUY_MARKET);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertThat(portfolio(1)).bodyJson().extractingPath("$.cashBalance").as("charged once").isEqualTo(743741.50);
    }

    @Test
    void retryOfANewLimitOrderDoesNotReserveTwice() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(1, key, BUY_LIMIT);

        MvcTestResult retry = order(1, key, BUY_LIMIT);

        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(retry).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        // 627,500 - 10 x 900.50, reserved once.
        assertThat(portfolio(1)).bodyJson().extractingPath("$.availableCash").isEqualTo(618495.00);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void retryOfARejectedOrderReproducesItsRejectionReason() {
        UUID key = UUID.randomUUID();
        String tooBig = """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1000}""";
        MvcTestResult first = order(1, key, tooBig);
        assertThat(first).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");

        MvcTestResult retry = order(1, key, tooBig);

        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(retry).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(retry).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void retryAfterCancellingReturnsTheCreationResultWhileTheOrderStaysCancelled() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(1, key, BUY_LIMIT);
        int orderId = JsonPath.read(body(first), "$.id");
        assertThat(mvc.post().uri("/api/v1/users/1/orders/{id}/cancel", orderId).exchange()).hasStatusOk();

        MvcTestResult retry = order(1, key, BUY_LIMIT);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(retry).bodyJson().extractingPath("$.status").as("result of the original creation").isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("current state").isEqualTo("CANCELLED");
        assertThat(portfolio(1)).bodyJson().extractingPath("$.availableCash").as("the retry reserves nothing")
                .isEqualTo(627500.00);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void equivalentNumberRepresentationsAreTheSameRequest() {
        UUID key = UUID.randomUUID();
        order(1, key, BUY_LIMIT);

        MvcTestResult retry = order(1, key, """
                {"price": 900.5, "quantity": 10, "type": "LIMIT", "side": "BUY", "instrumentId": 47}""");

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void sameKeyWithADifferentRequestIsAConflict() {
        UUID key = UUID.randomUUID();
        order(1, key, BUY_MARKET);

        MvcTestResult reused = order(1, key, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 11}""");

        assertThat(reused).hasStatus(HttpStatus.CONFLICT);
        assertThat(reused).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void differentKeysAreDifferentOrders() {
        order(1, UUID.randomUUID(), BUY_MARKET);
        order(1, UUID.randomUUID(), BUY_MARKET);

        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 2);
        assertThat(idempotencyCount()).isEqualTo(2);
    }

    @Test
    void theSameKeyBelongsToEachUserSeparately() {
        UUID key = UUID.randomUUID();

        MvcTestResult user1 = order(1, key, BUY_LIMIT);
        MvcTestResult user2 = order(2, key, BUY_LIMIT);

        assertThat(user1).doesNotContainHeader("Idempotent-Replayed");
        assertThat(user2).doesNotContainHeader("Idempotent-Replayed");
        assertThat(user2).bodyJson().extractingPath("$.userId").isEqualTo(2);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 2);
    }

    @Test
    void errorsAreNotStoredSoTheKeyCanBeRetried() {
        UUID key = UUID.randomUUID();
        MvcTestResult notExecutable = order(1, key, """
                {"instrumentId": 3, "side": "BUY", "type": "MARKET", "quantity": 1}""");
        assertThat(notExecutable).hasStatus(422);
        assertThat(idempotencyCount()).isZero();

        MvcTestResult retry = order(1, key, BUY_MARKET);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).doesNotContainHeader("Idempotent-Replayed");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void theHeaderIsRequired() {
        MvcTestResult result = mvc.post().uri("/api/v1/users/1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(BUY_MARKET).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("Idempotency-Key");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS);
    }

    private MvcTestResult order(int userId, UUID key, String body) {
        return mvc.post().uri("/api/v1/users/{userId}/orders", userId).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult portfolio(int userId) {
        return mvc.get().uri("/api/v1/users/{userId}/portfolio", userId).exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Integer orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    private Integer idempotencyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Integer.class);
    }
}
