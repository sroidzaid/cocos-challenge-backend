package com.cocos.challenge.instruments.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cocos.challenge.instruments.domain.Instrument;

public interface InstrumentRepositoryPort {

    Optional<Instrument> findById(int id);

    /** Batch lookup (one query) to avoid N+1 when valuing positions. Unknown ids are simply absent. */
    List<Instrument> findAllByIds(Collection<Integer> ids);

    /** Exact, case-insensitive ticker match. Fails if the (non-unique) ticker matches several instruments. */
    Optional<Instrument> findByTicker(String ticker);

    /**
     * Market assets whose ticker OR name contains {@code text} (case-insensitive; {@code %} and {@code _} typed by the
     * user are literal). Currency instruments (MONEDA) are excluded: they are not tradable assets.
     * Ordered by relevance: exact ticker, ticker prefix, then the rest (ties by ticker).
     */
    List<Instrument> search(String text, int limit);

    /** The local currency instrument (type MONEDA, ticker ARS), resolved without assuming a fixed id. */
    Optional<Instrument> findLocalCurrency();
}
