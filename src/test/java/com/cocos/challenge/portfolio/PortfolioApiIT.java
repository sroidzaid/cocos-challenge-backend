package com.cocos.challenge.portfolio;

import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.portfolio.domain.Holding;
import com.cocos.challenge.portfolio.domain.PositionCalculator;
import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.support.SeedDataRestorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end portfolio over HTTP. The original seed of database.sql is the oracle (user 1, quotes of 2023-07-14):
 * <ul>
 *     <li>cash = 1,000,000 - 46,500 + 9,400 - 100,000 - 30,800 - 125,000 + 45,900 = 753,000.00;</li>
 *     <li>reserved by NEW BUY = 50 x 710 + 60 x 1,500 = 125,500 -> available 627,500.00;</li>
 *     <li>PAMP: buy 50 @ 930, sell 10 @ 940 -> 40 @ 930; 40 x 925.85 = 37,034.00; -166 / 37,200 = -0.45%;</li>
 *     <li>METR: 500 @ 250; 500 x 229.50 = 114,750.00; -8.20%;</li>
 *     <li>BMA: buy 20 @ 1,540, sell 30 @ 1,530 -> -10 (inconsistent data); -10 x 1,502.80 = -15,028.00;</li>
 *     <li>total = 753,000 + 37,034 + 114,750 - 15,028 = 889,756.00.</li>
 * </ul>
 */
@ApplicationIT
class PortfolioApiIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SeedDataRestorer seed;

    @Autowired
    private LedgerQuery ledger;

    @AfterEach
    void restoreSeed() {
        seed.restoreOrders();
    }

    @Test
    void portfolioOfTheSeedUser() {
        MvcTestResult result = portfolio(1);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "userId": 1,
                  "totalAccountValue": 889756.00,
                  "cashBalance": 753000.00,
                  "availableCash": 627500.00,
                  "valuationComplete": true,
                  "positions": [
                    {"instrumentId": 31, "ticker": "BMA", "name": "Banco Macro S.A.", "quantity": -10,
                     "averageCost": null, "lastPrice": 1502.80, "marketValue": -15028.00,
                     "totalReturnPercent": null, "valuationStatus": "NEGATIVE_QUANTITY"},
                    {"instrumentId": 54, "ticker": "METR", "name": "MetroGAS S.A.", "quantity": 500,
                     "averageCost": 250.00, "lastPrice": 229.50, "marketValue": 114750.00,
                     "totalReturnPercent": -8.20, "valuationStatus": "OK"},
                    {"instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "quantity": 40,
                     "averageCost": 930.00, "lastPrice": 925.85, "marketValue": 37034.00,
                     "totalReturnPercent": -0.45, "valuationStatus": "OK"}
                  ],
                  "warnings": [
                    {"code": "NEGATIVE_QUANTITY", "instrumentId": 31,
                     "message": "BMA has a negative quantity (more shares sold than bought in the order history): average cost and return are not computed"}
                  ]
                }""");
        assertThat(result).body().asString()
                .contains("\"totalAccountValue\":889756.00", "\"availableCash\":627500.00");
    }

    @Test
    void userWithoutOrdersHasAnEmptyPortfolio() {
        MvcTestResult result = portfolio(2);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"userId": 2, "totalAccountValue": 0.00, "cashBalance": 0.00, "availableCash": 0.00,
                 "valuationComplete": true, "positions": [], "warnings": []}""");
    }

    @Test
    void unknownUserIsNotFound() {
        MvcTestResult result = portfolio(999);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void positionWithoutMarketDataMakesTheTotalAPartialKnownValue() {
        // PGR (id 3) has no market data in the seed.
        jdbc.update("""
                INSERT INTO orders (instrumentid, userid, size, price, type, side, status, datetime) VALUES
                  (66, 2, 10000, 1, 'MARKET', 'CASH_IN', 'FILLED', '2023-07-14 10:00:00'),
                  (3, 2, 10, 100, 'MARKET', 'BUY', 'FILLED', '2023-07-14 10:01:00'),
                  (47, 2, 2, 900, 'MARKET', 'BUY', 'FILLED', '2023-07-14 10:02:00')""");

        MvcTestResult result = portfolio(2);

        assertThat(result).hasStatusOk();
        // cash = 10,000 - 1,000 - 1,800 = 7,200; total = 7,200 + 2 x 925.85 (PGR excluded) = 9,051.70.
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "userId": 2,
                  "totalAccountValue": 9051.70,
                  "cashBalance": 7200.00,
                  "availableCash": 7200.00,
                  "valuationComplete": false,
                  "positions": [
                    {"instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "quantity": 2,
                     "averageCost": 900.00, "lastPrice": 925.85, "marketValue": 1851.70,
                     "totalReturnPercent": 2.87, "valuationStatus": "OK"},
                    {"instrumentId": 3, "ticker": "PGR", "name": "Phoenix Global Resources", "quantity": 10,
                     "averageCost": 100.00, "lastPrice": null, "marketValue": null,
                     "totalReturnPercent": null, "valuationStatus": "NO_MARKET_PRICE"}
                  ],
                  "warnings": [
                    {"code": "NO_MARKET_PRICE", "instrumentId": 3,
                     "message": "PGR has no market data: it is not valued and not included in the total account value"}
                  ]
                }""");
    }

    @Test
    void calculatorQuantitiesMatchTheSqlLedger() {
        assertThat(PositionCalculator.holdings(ledger.filledOrders(1)))
                .extracting(h -> new NetPosition(h.instrumentId(), h.quantity()))
                .containsExactlyElementsOf(ledger.netPositions(1).stream().filter(p -> p.quantity() != 0).toList());
        assertThat(PositionCalculator.holdings(ledger.filledOrders(1))).extracting(Holding::quantity)
                .containsExactly(-10L, 40L, 500L);
    }

    private MvcTestResult portfolio(int userId) {
        return mvc.get().uri("/api/v1/users/{userId}/portfolio", userId).exchange();
    }
}
