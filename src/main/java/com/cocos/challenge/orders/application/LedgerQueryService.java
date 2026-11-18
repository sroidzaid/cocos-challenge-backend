package com.cocos.challenge.orders.application;

import java.util.List;
import java.util.Set;

import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.orders.application.port.out.LedgerQueryPort;
import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.Reservations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Thin read-only bridge between other modules and the persistence ports (not a use case). */
@Service
@Transactional(readOnly = true)
class LedgerQueryService implements LedgerQuery {

    private final LedgerQueryPort ledger;
    private final OrderRepositoryPort orders;

    LedgerQueryService(LedgerQueryPort ledger, OrderRepositoryPort orders) {
        this.ledger = ledger;
        this.orders = orders;
    }

    @Override
    public CashBalance cashBalance(int userId) {
        return ledger.cashBalance(userId);
    }

    @Override
    public List<NetPosition> netPositions(int userId) {
        return ledger.netPositions(userId);
    }

    @Override
    public Reservations reservations(int userId) {
        return ledger.reservations(userId);
    }

    @Override
    public List<Order> filledOrders(int userId) {
        return orders.findByUserIdAndStatusIn(userId, Set.of(OrderStatus.FILLED));
    }
}
