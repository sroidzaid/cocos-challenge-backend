package com.cocos.challenge.marketdata.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cocos.challenge.marketdata.application.port.in.MarketDataQuery;
import com.cocos.challenge.marketdata.application.port.out.MarketDataPort;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thin read-only bridge between other modules and the persistence port (not a use case). */
@Service
@Transactional(readOnly = true)
class MarketDataQueryService implements MarketDataQuery {

    private final MarketDataPort marketData;

    MarketDataQueryService(MarketDataPort marketData) {
        this.marketData = marketData;
    }

    @Override
    public Optional<MarketQuote> findLatestByInstrumentId(int instrumentId) {
        return marketData.findLatestByInstrumentId(instrumentId);
    }

    @Override
    public Map<Integer, MarketQuote> findLatestByInstrumentIds(Collection<Integer> instrumentIds) {
        return marketData.findLatestByInstrumentIds(instrumentIds);
    }

    @Override
    public List<MarketQuote> findByInstrumentId(int instrumentId) {
        return marketData.findByInstrumentId(instrumentId);
    }
}
