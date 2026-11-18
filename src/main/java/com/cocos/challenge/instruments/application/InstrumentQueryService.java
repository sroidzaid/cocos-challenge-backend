package com.cocos.challenge.instruments.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cocos.challenge.instruments.application.port.in.InstrumentQuery;
import com.cocos.challenge.instruments.application.port.out.InstrumentRepositoryPort;
import com.cocos.challenge.instruments.domain.Instrument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thin read-only bridge between other modules and the persistence port (not a use case). */
@Service
@Transactional(readOnly = true)
class InstrumentQueryService implements InstrumentQuery {

    private final InstrumentRepositoryPort instruments;

    InstrumentQueryService(InstrumentRepositoryPort instruments) {
        this.instruments = instruments;
    }

    @Override
    public Optional<Instrument> findById(int id) {
        return instruments.findById(id);
    }

    @Override
    public List<Instrument> findAllByIds(Collection<Integer> ids) {
        return instruments.findAllByIds(ids);
    }

    @Override
    public Optional<Instrument> findByTicker(String ticker) {
        return instruments.findByTicker(ticker);
    }

    @Override
    public Optional<Instrument> findLocalCurrency() {
        return instruments.findLocalCurrency();
    }
}
