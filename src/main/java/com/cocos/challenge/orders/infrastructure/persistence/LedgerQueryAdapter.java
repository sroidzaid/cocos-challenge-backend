package com.cocos.challenge.orders.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cocos.challenge.orders.application.port.out.LedgerQueryPort;
import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Reservations;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import org.springframework.stereotype.Component;

@Component
class LedgerQueryAdapter implements LedgerQueryPort {

    private final OrderJpaRepository repository;

    LedgerQueryAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public CashBalance cashBalance(int userId) {
        OrderJpaRepository.CashAggregate aggregate = repository.cashAggregate(userId);
        if (aggregate.getAnomalies() > 0) {
            throw new PersistedDataIntegrityException("User " + userId + " has " + aggregate.getAnomalies()
                    + " FILLED order(s) with unknown side or missing size/price; refusing to compute cash");
        }
        return new CashBalance(aggregate.getCash());
    }

    @Override
    public List<NetPosition> netPositions(int userId) {
        return repository.netPositions(userId).stream()
                .map(row -> {
                    if (row.getInstrumentId() == null || row.getAnomalies() > 0) {
                        throw new PersistedDataIntegrityException("User " + userId
                                + " has FILLED BUY/SELL order(s) without instrument or size; refusing to compute"
                                + " positions");
                    }
                    return new NetPosition(row.getInstrumentId(), row.getNetQuantity());
                })
                .toList();
    }

    @Override
    public Reservations reservations(int userId) {
        OrderJpaRepository.CashAggregate cash = repository.reservedCashAggregate(userId);
        if (cash.getAnomalies() > 0) {
            throw new PersistedDataIntegrityException("User " + userId + " has " + cash.getAnomalies()
                    + " NEW order(s) with unsupported side or missing instrument/size/price; refusing to compute"
                    + " reservations");
        }
        Map<Integer, Long> shares = new LinkedHashMap<>();
        repository.reservedShares(userId).forEach(row -> shares.put(row.getInstrumentId(), row.getShares()));
        return new Reservations(cash.getCash(), shares);
    }
}
