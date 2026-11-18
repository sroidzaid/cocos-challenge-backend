package com.cocos.challenge.marketdata.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cocos.challenge.marketdata.domain.MarketQuote;

public interface MarketDataPort {

    /** Most recent quote (highest date; ties broken by highest id, since there is no unique (instrument, date)). */
    Optional<MarketQuote> findLatestByInstrumentId(int instrumentId);

    /** Most recent quote per instrument in a single query. Instruments without quotes are absent from the map. */
    Map<Integer, MarketQuote> findLatestByInstrumentIds(Collection<Integer> instrumentIds);

    /** Full history of an instrument, most recent first. */
    List<MarketQuote> findByInstrumentId(int instrumentId);
}
