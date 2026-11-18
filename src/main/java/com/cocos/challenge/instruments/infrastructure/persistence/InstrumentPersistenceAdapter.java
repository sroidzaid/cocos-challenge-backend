package com.cocos.challenge.instruments.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cocos.challenge.instruments.application.port.out.InstrumentRepositoryPort;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.InstrumentType;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import org.springframework.stereotype.Component;

@Component
class InstrumentPersistenceAdapter implements InstrumentRepositoryPort {

    private final InstrumentJpaRepository repository;

    InstrumentPersistenceAdapter(InstrumentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Instrument> findById(int id) {
        return repository.findById(id).map(InstrumentMapper::toDomain);
    }

    @Override
    public List<Instrument> findAllByIds(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(ids).stream().map(InstrumentMapper::toDomain).toList();
    }

    @Override
    public Optional<Instrument> findByTicker(String ticker) {
        return single(repository.findAllByTickerIgnoreCase(ticker), "ticker " + ticker);
    }

    @Override
    public List<Instrument> search(String text, int limit) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("search text must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String escaped = escapeLike(text.strip());
        return repository.search(text.strip(), "%" + escaped + "%", escaped + "%", InstrumentType.MONEDA.name(), limit)
                .stream()
                .map(InstrumentMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Instrument> findLocalCurrency() {
        return single(repository.findAllByTypeAndTickerIgnoreCase(
                InstrumentType.MONEDA.name(), Instrument.LOCAL_CURRENCY_TICKER), "local currency instrument");
    }

    /** Escapes LIKE metacharacters typed by the user so they are matched literally. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Optional<Instrument> single(List<InstrumentEntity> matches, String description) {
        if (matches.size() > 1) {
            throw new PersistedDataIntegrityException(
                    "Expected a single instrument for " + description + " but found " + matches.size());
        }
        return matches.stream().findFirst().map(InstrumentMapper::toDomain);
    }
}
