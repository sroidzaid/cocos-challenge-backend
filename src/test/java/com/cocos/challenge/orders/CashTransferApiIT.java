package com.cocos.challenge.orders;

import java.io.UnsupportedEncodingException;
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
 * CASH_IN/CASH_OUT through POST /orders against PostgreSQL, checking the resulting cash in the portfolio.
 * Seed user 1: cash 753,000; NEW BUY orders reserve 125,500 (50 x 710 + 60 x 1,500); available 627,500.
 */
@ApplicationIT
class CashTransferApiIT {

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
    void cashInIsFilledOnTheCurrencyAndIncreasesTheCash() {
        MvcTestResult result = order(1, UUID.randomUUID(), transfer("CASH_IN", "5000"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).doesNotContainHeader("Idempotent-Replayed");
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {"id": 12, "userId": 1, "instrumentId": 66, "side": "CASH_IN", "type": "MARKET", "status": "FILLED",
                 "size": 5000, "price": 1.00, "rejectionReason": null}""");
        assertCash(1, "758000.00", "632500.00");
        assertThat(jdbc.queryForObject("""
                SELECT instrumentid || ':' || size || ':' || price || ':' || type || ':' || side || ':' || status
                FROM orders WHERE id = 12""", String.class)).isEqualTo("66:5000:1.00:MARKET:CASH_IN:FILLED");
    }

    @Test
    void cashOutWithinTheAvailableCashIsFilledAndDecreasesTheCash() {
        MvcTestResult result = order(1, UUID.randomUUID(), transfer("CASH_OUT", "3000"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertThat(result).bodyJson().extractingPath("$.side").isEqualTo("CASH_OUT");
        assertCash(1, "750000.00", "624500.00");
    }

    @Test
    void cashOutWithoutFundsIsPersistedAsRejectedAndLeavesTheCashUnchanged() {
        // User 2 has no orders: cash 0.
        MvcTestResult result = order(2, UUID.randomUUID(), transfer("CASH_OUT", "1"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertCash(2, "0.00", "0.00");
    }

    @Test
    void cashOutCannotWithdrawCashReservedByNewLimitOrders() {
        // 627,501 <= cash (753,000) but > available (753,000 - 125,500 reserved).
        MvcTestResult tooMuch = order(1, UUID.randomUUID(), transfer("CASH_OUT", "627501"));
        assertThat(tooMuch).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(tooMuch).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
        assertCash(1, "753000.00", "627500.00");

        MvcTestResult all = order(1, UUID.randomUUID(), transfer("CASH_OUT", "627500"));

        assertThat(all).bodyJson().extractingPath("$.status").isEqualTo("FILLED");
        assertCash(1, "125500.00", "0.00");
    }

    @Test
    void duplicatedCashInWithTheSameKeyCreditsOnce() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(1, key, transfer("CASH_IN", "5000"));

        MvcTestResult retry = order(1, key, transfer("CASH_IN", "5000"));

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertThat(idempotencyCount()).isEqualTo(1);
        assertCash(1, "758000.00", "632500.00");
    }

    @Test
    void duplicatedCashOutWithTheSameKeyDebitsOnce() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(1, key, transfer("CASH_OUT", "3000"));

        MvcTestResult retry = order(1, key, transfer("CASH_OUT", "3000"));

        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertCash(1, "750000.00", "624500.00");
    }

    @Test
    void duplicatedRejectedCashOutReproducesTheRejection() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = order(2, key, transfer("CASH_OUT", "100"));

        MvcTestResult retry = order(2, key, transfer("CASH_OUT", "100"));

        assertThat(retry).hasHeader("Idempotent-Replayed", "true");
        assertThat(retry).bodyJson().isStrictlyEqualTo(body(first));
        assertThat(retry).bodyJson().extractingPath("$.rejectionReason").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflict() {
        UUID key = UUID.randomUUID();
        order(1, key, transfer("CASH_IN", "5000"));

        MvcTestResult otherAmount = order(1, key, transfer("CASH_IN", "6000"));
        MvcTestResult otherSide = order(1, key, transfer("CASH_OUT", "5000"));
        MvcTestResult trade = order(1, key, """
                {"instrumentId": 47, "side": "BUY", "type": "MARKET", "amount": 5000}""");

        for (MvcTestResult conflict : new MvcTestResult[] {otherAmount, otherSide, trade}) {
            assertThat(conflict).hasStatus(HttpStatus.CONFLICT);
            assertThat(conflict).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
        assertThat(orderCount()).isEqualTo(SEED_ORDERS + 1);
        assertCash(1, "758000.00", "632500.00");
    }

    @Test
    void invalidAmountsAreValidationErrorsAndPersistNothing() {
        for (String body : new String[] {
                """
                {"side": "CASH_IN"}""",
                transfer("CASH_IN", "0"),
                transfer("CASH_OUT", "-10"),
                transfer("CASH_IN", "10.5"),
                transfer("CASH_IN", "2147483648"),
                """
                {"instrumentId": 66, "side": "CASH_IN", "amount": 100}""",
                """
                {"side": "CASH_OUT", "type": "MARKET", "amount": 100}"""}) {
            MvcTestResult result = order(1, UUID.randomUUID(), body);

            assertThat(result).as(body).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        }
        assertThat(orderCount()).isEqualTo(SEED_ORDERS);
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void transferOfAnUnknownUserIsNotFound() {
        MvcTestResult result = order(999, UUID.randomUUID(), transfer("CASH_IN", "100"));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("USER_NOT_FOUND");
        assertThat(orderCount()).isEqualTo(SEED_ORDERS);
    }

    @Test
    void transfersOnlyMoveTheCashNeverThePositions() {
        String positionsBefore = JsonPath.parse(body(portfolio(1))).read("$.positions").toString();

        order(1, UUID.randomUUID(), transfer("CASH_IN", "20000"));
        order(1, UUID.randomUUID(), transfer("CASH_OUT", "5000"));

        String after = body(portfolio(1));
        assertThat(JsonPath.parse(after).read("$.positions").toString()).isEqualTo(positionsBefore);
        // 889,756 + 20,000 - 5,000: the total moves only by the net transfer.
        assertThat(portfolio(1)).bodyJson().extractingPath("$.totalAccountValue").isEqualTo(904756.00);
        assertCash(1, "768000.00", "642500.00");
    }

    private void assertCash(int userId, String cashBalance, String availableCash) {
        MvcTestResult portfolio = portfolio(userId);
        assertThat(portfolio).hasStatusOk();
        assertThat(portfolio).body().asString().contains("\"cashBalance\":" + cashBalance,
                "\"availableCash\":" + availableCash);
    }

    private static String transfer(String side, String amount) {
        return "{\"side\": \"" + side + "\", \"amount\": " + amount + "}";
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
        } catch (UnsupportedEncodingException e) {
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
