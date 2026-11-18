package com.cocos.challenge.instruments.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.cocos.challenge.instruments.application.port.in.InstrumentSearchResult;
import com.cocos.challenge.instruments.application.port.out.InstrumentRepositoryPort;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchInstrumentsServiceTest {

    private static final Instrument BMA = new Instrument(31, "BMA", "Banco Macro S.A.", InstrumentType.ACCIONES);
    private static final Instrument PGR = new Instrument(3, "PGR", "Phoenix Global Resources", InstrumentType.ACCIONES);

    @Mock
    private InstrumentRepositoryPort instruments;

    @Mock
    private MarketDataQuery marketData;

    @InjectMocks
    private SearchInstrumentsService service;

    @Test
    void attachesTheLatestQuoteKeepingRelevanceOrder() {
        when(instruments.search("b", 20)).thenReturn(List.of(BMA, PGR));
        when(marketData.findLatestByInstrumentIds(List.of(31, 3))).thenReturn(Map.of(31,
                new MarketQuote(31, LocalDate.of(2023, 7, 14), new BigDecimal("1502.80"), new BigDecimal("1520.25"))));

        List<InstrumentSearchResult> results = service.search("b", 20);

        assertThat(results).extracting(r -> r.instrument().ticker()).containsExactly("BMA", "PGR");
        assertThat(results.get(0).lastPrice()).isEqualByComparingTo("1502.80");
        assertThat(results.get(0).dailyReturnPercent()).isEqualByComparingTo("-1.15");
        assertThat(results.get(1).latestQuote()).isNull();
        assertThat(results.get(1).lastPrice()).isNull();
        assertThat(results.get(1).dailyReturnPercent()).isNull();
    }

    @Test
    void doesNotQueryMarketDataWhenNothingMatches() {
        when(instruments.search("zzz", 20)).thenReturn(List.of());

        assertThat(service.search("zzz", 20)).isEmpty();
        verify(marketData, never()).findLatestByInstrumentIds(any());
    }
}
