package com.cocos.challenge.instruments;

import com.cocos.challenge.support.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end search over HTTP against the seed of database.sql (read-only). */
@ApplicationIT
class InstrumentSearchApiIT {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void searchesByNameWithLatestPriceAndDailyReturn() {
        MvcTestResult result = search("banco");

        assertThat(result).hasStatusOk();
        // close / previousClose of 2023-07-14 in the seed; daily return rounded HALF_EVEN to 2 decimals.
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [
                  {"id": 22, "ticker": "BBAR", "name": "Banco Frances",           "lastPrice": 1038.10, "dailyReturnPercent": -0.87},
                  {"id": 28, "ticker": "BHIP", "name": "Banco Hipotecario S.A.",  "lastPrice": 33.40,   "dailyReturnPercent": -3.47},
                  {"id": 31, "ticker": "BMA",  "name": "Banco Macro S.A.",        "lastPrice": 1502.80, "dailyReturnPercent": -1.15},
                  {"id": 19, "ticker": "BPAT", "name": "Banco Patagonia",         "lastPrice": 279.00,  "dailyReturnPercent": -3.46}
                ]""");
    }

    @Test
    void searchesByTickerWithExactMatchFirst() {
        MvcTestResult result = search("ggal");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[0].ticker").isEqualTo("GGAL");
        assertThat(result).bodyJson().extractingPath("$[0].name").isEqualTo("Grupo Financiero Galicia");
    }

    @Test
    void instrumentWithoutMarketDataHasNullPrice() {
        MvcTestResult result = search("PGR");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [{"id": 3, "ticker": "PGR", "name": "Phoenix Global Resources", "lastPrice": null, "dailyReturnPercent": null}]""");
    }

    @Test
    void currencyIsNotAMarketAsset() {
        assertThat(search("ars")).bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void noMatchesIsAnEmptyArray() {
        MvcTestResult result = search("zzz");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void invalidParametersAreRejected() {
        MvcTestResult result = mvc.get().uri("/api/v1/instruments").param("q", "").exchange();

        assertThat(result).hasStatus4xxClientError();
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    private MvcTestResult search(String q) {
        return mvc.get().uri("/api/v1/instruments").param("q", q).exchange();
    }
}
