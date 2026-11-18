package com.cocos.challenge.marketdata.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cocos.challenge.marketdata.application.port.out.MarketDataPort;
import com.cocos.challenge.marketdata.domain.MarketQuote;
import org.springframework.stereotype.Component;

@Component
class MarketDataPersistenceAdapter implements MarketDataPort {

    private final MarketDataJpaRepository repository;

    MarketDataPersistenceAdapter(MarketDataJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MarketQuote> findLatestByInstrumentId(int instrumentId) {
        return Optional.ofNullable(findLatestByInstrumentIds(List.of(instrumentId)).get(instrumentId));
    }

    @Override
    public Map<Integer, MarketQuote> findLatestByInstrumentIds(Collection<Integer> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }
        return repository.findLatestByInstrumentIds(instrumentIds).stream()
                .map(MarketDataPersistenceAdapter::toDomain)
                .collect(Collectors.toUnmodifiableMap(MarketQuote::instrumentId, Function.identity()));
    }

    @Override
    public List<MarketQuote> findByInstrumentId(int instrumentId) {
        return repository.findByInstrumentIdOrderByDateDescIdDesc(instrumentId).stream()
                .map(MarketDataPersistenceAdapter::toDomain)
                .toList();
    }

    private static MarketQuote toDomain(MarketDataEntity entity) {
        return new MarketQuote(entity.getInstrumentId(), entity.getDate(), entity.getClose(),
                entity.getPreviousClose());
    }
}
