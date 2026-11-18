package com.cocos.challenge.orders;

import java.util.UUID;

import com.cocos.challenge.support.ApplicationIT;
import com.jayway.jsonpath.JsonPath;
import com.cocos.challenge.support.SeedDataRestorer;
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
 * Functional test of order placement and cancellation over HTTP against PostgreSQL, checking the effect through the
 * portfolio endpoint. Seed user 1: cash 753,000.00, reserved 125,500 (available 627,500), PAMP 40 @ 930 (close
 * 925.85), BMA -10, NEW orders 5 and 7. Every test restores the original orders.
 */
@ApplicationIT
class OrderApiIT {

    private static final int SEED_ORDERS = 11;

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
    void buyMarketIsFilledAtTheLastCloseAndUpdatesCashPositionAndAverageCost() {
        MvcTestResult result = order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 10}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertThat(result).bodyJson().extractingPath("$.size").isEqualTo(10);
        assertThat(result).bodyJson().extractingPath("$.price").isEqualTo(925.85);
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isNull();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(12);

        // cash 753,000 - 9,258.50; average (40 x 930 + 10 x 925.85) / 50 = 929.17.
        MvcTestResult portfolio = portfolio(1);
        assertThat(portfolio).bodyJson().extractingPath("$.cashBalance").isEqualTo(743741.50);
        assertThat(portfolio).bodyJson().extractingPath("$.availableCash").isEqualTo(618241.50);
        assertThat(portfolio).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].quantity").asArray()
                .containsExactly(50);
        assertThat(portfolio).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].averageCost").asArray()
                .containsExactly(929.17);
    }

    @Test
    void sellMarketIsFilledAndIncreasesCashKeepingTheAverageCost() {
        MvcTestResult result = order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 10}""");

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        MvcTestResult portfolio = portfolio(1);
        assertThat(portfolio).bodyJson().extractingPath("$.cashBalance").isEqualTo(762258.50);
        assertThat(portfolio).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].quantity").asArray()
                .containsExactly(30);
        assertThat(portfolio).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].averageCost").asArray()
                .containsExactly(930.00);
    }

    @Test
    void buyLimitStaysNewAndReservesCashWithoutChangingTheBalance() {
        MvcTestResult result = order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "LIMIT", "quantity": 10, "price": 900}""");

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("NEW");
        assertThat(result).bodyJson().extractingPath("$.price").isEqualTo(900.00);
        MvcTestResult portfolio = portfolio(1);
        assertThat(portfolio).bodyJson().extractingPath("$.cashBalance").isEqualTo(753000.00);
        assertThat(portfolio).bodyJson().extractingPath("$.availableCash").isEqualTo(618500.00);
        assertThat(portfolio).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].quantity").asArray()
                .containsExactly(40);
    }

    @Test
    void sellLimitReservesSharesForLaterOrders() {
        assertThat(order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "LIMIT", "quantity": 30, "price": 1000}"""))
                .bodyJson().extractingPath("$.status").isEqualTo("NEW");

        MvcTestResult tooMany = order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 20}""");
        assertThat(tooMany).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(tooMany).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_HOLDINGS");

        assertThat(order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 10}"""))
                .bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertThat(portfolio(1)).bodyJson().extractingPath("$.positions[?(@.ticker=='PAMP')].quantity").asArray()
                .as("the NEW sell does not change executed holdings").containsExactly(30);
    }

    @Test
    void amountIsConvertedToWholeSharesRoundingDown() {
        // floor(10,000 / 925.85) = floor(10.80) = 10.
        MvcTestResult result = order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 10000}""");

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertThat(result).bodyJson().extractingPath("$.size").isEqualTo(10);
    }

    @Test
    void insufficientFundsIsPersistedAsRejectedAndDoesNotChangeTheAccount() {
        // 1,000 x 925.85 = 925,850 > 627,500 available.
        MvcTestResult result = order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1000}""");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 12", String.class)).isEqualTo("REJECTED");
        MvcTestResult portfolio = portfolio(1);
        assertThat(portfolio).bodyJson().extractingPath("$.totalAccountValue").isEqualTo(889756.00);
        assertThat(portfolio).bodyJson().extractingPath("$.availableCash").isEqualTo(627500.00);
    }

    @Test
    void negativeSeedPositionCannotBeSoldAndIsKept() {
        MvcTestResult result = order(1, """
                {"instrumentId": 31, "side": "SELL", "type": "MARKET", "quantity": 1}""");

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_HOLDINGS");
        assertThat(portfolio(1)).bodyJson().extractingPath("$.positions[?(@.ticker=='BMA')].quantity").asArray()
                .containsExactly(-10);
    }

    @Test
    void nonExecutableOrdersAreErrorsAndNothingIsPersisted() {
        assertError(order(1, """
                {"instrumentId": 66, "side": "BUY", "type": "MARKET", "quantity": 1}"""), 422, "INSTRUMENT_NOT_TRADABLE");
        assertError(order(1, """
                {"instrumentId": 3, "side": "BUY", "type": "MARKET", "quantity": 1}"""), 422, "MARKET_PRICE_UNAVAILABLE");
        assertError(order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 100}"""), 422, "AMOUNT_TOO_LOW");
        assertError(order(1, """
                {"instrumentId": 9999, "side": "BUY", "type": "MARKET", "quantity": 1}"""), 404, "INSTRUMENT_NOT_FOUND");
        assertError(order(999, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1}"""), 404, "USER_NOT_FOUND");
        assertError(order(1, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 1, "price": 10}"""), 400,
                "VALIDATION_FAILED");

        assertThat(orderCount()).isEqualTo(SEED_ORDERS);
    }

    @Test
    void cancellingANewOrderReleasesItsReservation() {
        MvcTestResult result = cancel(1, 5);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": 5, "userId": 1, "instrumentId": 45, "side": "BUY", "type": "LIMIT", "status": "CANCELLED",
                 "size": 50, "price": 710.00, "datetime": "2023-07-12T15:14:20", "rejectionReason": null}""");
        // 627,500 + 50 x 710 released; no cash movement.
        MvcTestResult portfolio = portfolio(1);
        assertThat(portfolio).bodyJson().extractingPath("$.availableCash").isEqualTo(663000.00);
        assertThat(portfolio).bodyJson().extractingPath("$.cashBalance").isEqualTo(753000.00);
        assertThat(orderCount()).isEqualTo(SEED_ORDERS);
    }

    @Test
    void cancellingASellLimitReleasesTheShares() throws Exception {
        int orderId = idOf(order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "LIMIT", "quantity": 40, "price": 1000}"""));
        assertThat(order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 1}"""))
                .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");

        assertThat(cancel(1, orderId)).hasStatusOk();

        assertThat(order(1, """
                {"instrumentId": 47, "side": "SELL", "type": "MARKET", "quantity": 40}"""))
                .bodyJson().extractingPath("$.status").isEqualTo("FILLED");
    }

    @Test
    void onlyNewOrdersOfTheUserCanBeCancelled() {
        assertThat(cancel(1, 5)).hasStatusOk();
        assertError(cancel(1, 5), 409, "ORDER_NOT_CANCELLABLE");
        assertError(cancel(1, 1), 409, "ORDER_NOT_CANCELLABLE");
        assertError(cancel(1, 6), 409, "ORDER_NOT_CANCELLABLE");
        assertError(cancel(2, 7), 404, "ORDER_NOT_FOUND");
        assertError(cancel(1, 999), 404, "ORDER_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = 7", String.class)).isEqualTo("NEW");
    }

    private MvcTestResult order(int userId, String body) {
        // Each call is a new intention: a fresh idempotency key (replays are covered by OrderIdempotencyApiIT).
        return mvc.post().uri("/api/v1/users/{userId}/orders", userId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult cancel(int userId, int orderId) {
        return mvc.post().uri("/api/v1/users/{userId}/orders/{orderId}/cancel", userId, orderId).exchange();
    }

    private MvcTestResult portfolio(int userId) {
        MvcTestResult result = mvc.get().uri("/api/v1/users/{userId}/portfolio", userId).exchange();
        assertThat(result).hasStatusOk();
        return result;
    }

    private Integer orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    private static void assertError(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    private static int idOf(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
