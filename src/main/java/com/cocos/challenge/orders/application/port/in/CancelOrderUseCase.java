package com.cocos.challenge.orders.application.port.in;

import com.cocos.challenge.orders.domain.Order;

public interface CancelOrderUseCase {

    /**
     * Cancels a NEW order of the user, which releases its reservation.
     *
     * @return the order in CANCELLED status
     * @throws com.cocos.challenge.shared.domain.NotFoundException USER_NOT_FOUND, ORDER_NOT_FOUND (also for orders of
     *                                                             other users)
     * @throws com.cocos.challenge.shared.domain.ConflictException ORDER_NOT_CANCELLABLE (not NEW)
     */
    Order cancel(int userId, int orderId);
}
