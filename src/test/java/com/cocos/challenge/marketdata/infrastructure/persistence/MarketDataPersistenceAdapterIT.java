package com.cocos.challenge.marketdata.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.cocos.challenge.marketdata.domain.MarketQuote;
import com.cocos.challenge.support.PersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@PersistenceIT
@Import(MarketDataPersistenceAdapter.class)
class MarketDataPersistenceAdapterIT {

    private static final LocalDate LAST_DAY = LocalDate.of(2023, 7, 14);

    @Autowired
    private MarketDataPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void latestQuoteIsTheMostRecentDateWithExactValues() {
        assertThat(adapter.findLatestByInstrumentId(47)).contains(
                new MarketQuote(47, LAST_DAY, new BigDecimal("925.85"), new BigDecimal("921.80")));
    }

    @Test
    void previousCloseReflectsTheValueStoredByPostgres() {
        // database.sql inserts 1047.16600000 into NUMERIC(10,2): PostgreSQL silently stored 1047.17.
        assertThat(adapter.findLatestByInstrumentId(22))
                .map(MarketQuote::previousClose)
                .contains(new BigDecimal("1047.17"));
    }

    @Test
    void instrumentWithoutQuotesHasNoLatestQuote() {
        assertThat(adapter.findLatestByInstrumentId(3)).isEmpty();
    }

    @Test
    void latestQuotesForSeveralInstrumentsInOneQuery() {
        Map<Integer, MarketQuote> latest = adapter.findLatestByInstrumentIds(List.of(31, 47, 54, 3));

        assertThat(latest).containsOnlyKeys(31, 47, 54);
        assertThat(latest.values()).extracting(MarketQuote::date).containsOnly(LAST_DAY);
        assertThat(latest.get(31).close()).isEqualByComparingTo("1502.80");
        assertThat(adapter.findLatestByInstrumentIds(List.of())).isEmpty();
    }

    @Test
    void tiesOnTheSameDateAreBrokenByHighestId() {
        // The schema allows two rows for the same instrument and day; the most recently inserted one wins.
        jdbc.update("""
                INSERT INTO marketdata (instrumentid, date, close, previousclose)
                VALUES (47, '2023-07-14', 999.99, 925.85)""");

        assertThat(adapter.findLatestByInstrumentId(47)).map(MarketQuote::close).contains(new BigDecimal("999.99"));
    }

    @Test
    void historyIsOrderedMostRecentFirst() {
        assertThat(adapter.findByInstrumentId(47))
                .extracting(MarketQuote::date)
                .containsExactly(LAST_DAY, LocalDate.of(2023, 7, 13));
    }
}
