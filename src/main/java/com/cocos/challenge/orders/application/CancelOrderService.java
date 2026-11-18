package com.cocos.challenge.orders.application;

import com.cocos.challenge.orders.application.port.in.CancelOrderUseCase;
import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.NotFoundException;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels NEW orders. Same protocol as placing an order (account lock first, READ COMMITTED), plus a conditional
 * {@code UPDATE ... AND status = 'NEW'}: even a writer that bypasses the lock cannot make a FILLED order CANCELLED.
 * Cancelling releases the reservation by itself: reservations are derived from NEW orders, nothing else is written.
 */
@Service
class CancelOrderService implements CancelOrderUseCase {

    static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    static final String ORDER_NOT_CANCELLABLE = "ORDER_NOT_CANCELLABLE";

    private final AccountLock accountLock;
    private final OrderRepositoryPort orders;

    CancelOrderService(AccountLock accountLock, OrderRepositoryPort orders) {
        this.accountLock = accountLock;
        this.orders = orders;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order cancel(int userId, int orderId) {
        if (!accountLock.lock(userId)) {
            throw new NotFoundException(PlaceOrderService.USER_NOT_FOUND, "User " + userId + " does not exist");
        }
        Order order = orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException(ORDER_NOT_FOUND,
                        "Order " + orderId + " does not exist for user " + userId));
        if (!order.status().isCancellable()) {
            throw new ConflictException(ORDER_NOT_CANCELLABLE,
                    "Order " + orderId + " is " + order.status() + "; only NEW orders can be cancelled");
        }
        if (!orders.cancelIfNew(orderId, userId)) {
            // Only reachable if a writer that does not take the account lock changed the order meanwhile.
            throw new ConflictException(ORDER_NOT_CANCELLABLE, "Order " + orderId + " is no longer NEW");
        }
        return order.withStatus(OrderStatus.CANCELLED);
    }
}
