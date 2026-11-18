package com.cocos.challenge.instruments.application;

import java.util.List;
import java.util.Map;

import com.cocos.challenge.instruments.application.port.in.InstrumentSearchResult;
import com.cocos.challenge.instruments.application.port.in.SearchInstrumentsUseCase;
import com.cocos.challenge.instruments.application.port.out.InstrumentRepositoryPort;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two queries regardless of the number of results: the matching instruments, then their latest quotes in batch
 * (no N+1). Both run in the same read-only transaction.
 */
@Service
@Transactional(readOnly = true)
class SearchInstrumentsService implements SearchInstrumentsUseCase {

    private final InstrumentRepositoryPort instruments;
    private final MarketDataQuery marketData;

    SearchInstrumentsService(InstrumentRepositoryPort instruments, MarketDataQuery marketData) {
        this.instruments = instruments;
        this.marketData = marketData;
    }

    @Override
    public List<InstrumentSearchResult> search(String text, int limit) {
        List<Instrument> matches = instruments.search(text, limit);
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<Integer, MarketQuote> quotes =
                marketData.findLatestByInstrumentIds(matches.stream().map(Instrument::id).toList());
        return matches.stream()
                .map(instrument -> new InstrumentSearchResult(instrument, quotes.get(instrument.id())))
                .toList();
    }
}
