package com.cocos.challenge.orders.application.port.in;

import java.util.List;

import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.Reservations;

/**
 * Read access to the orders ledger for other modules (portfolio). The orders module owns the table, so the
 * cash and holdings formulas live here only once.
 */
public interface LedgerQuery {

    CashBalance cashBalance(int userId);

    List<NetPosition> netPositions(int userId);

    /** Cash and shares held back by the user's NEW orders (decision D2). */
    Reservations reservations(int userId);

    /** FILLED orders of the user in chronological order (input for cost-basis calculations). */
    List<Order> filledOrders(int userId);
}
