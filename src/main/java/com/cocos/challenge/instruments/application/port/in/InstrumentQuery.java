package com.cocos.challenge.instruments.application.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cocos.challenge.instruments.domain.Instrument;

/** Read access to instruments for other modules (orders, portfolio). Searching is {@link SearchInstrumentsUseCase}. */
public interface InstrumentQuery {

    Optional<Instrument> findById(int id);

    List<Instrument> findAllByIds(Collection<Integer> ids);

    Optional<Instrument> findByTicker(String ticker);

    Optional<Instrument> findLocalCurrency();
}
