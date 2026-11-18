package com.cocos.challenge.marketdata.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cocos.challenge.marketdata.domain.MarketQuote;

/** Read access to market prices for other modules (orders, portfolio, instruments search). */
public interface MarketDataQuery {

    Optional<MarketQuote> findLatestByInstrumentId(int instrumentId);

    Map<Integer, MarketQuote> findLatestByInstrumentIds(Collection<Integer> instrumentIds);

    List<MarketQuote> findByInstrumentId(int instrumentId);
}
