package com.cocos.challenge.orders.application.port.out;

import java.util.List;

import com.cocos.challenge.orders.domain.CashBalance;
import com.cocos.challenge.orders.domain.NetPosition;
import com.cocos.challenge.orders.domain.Reservations;

/**
 * Aggregates over the user's FILLED orders, computed by the database (exact NUMERIC arithmetic, one row per
 * instrument). Implementations must fail instead of returning a figure when rows they cannot interpret are found.
 */
public interface LedgerQueryPort {

    CashBalance cashBalance(int userId);

    /** Net quantity per instrument from FILLED BUY/SELL orders, including zero and negative results. */
    List<NetPosition> netPositions(int userId);

    /** Cash and shares held back by the user's NEW orders (decision D2). */
    Reservations reservations(int userId);
}
