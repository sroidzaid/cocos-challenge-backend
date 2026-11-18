package com.cocos.challenge.orders.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderStatus;

public interface OrderRepositoryPort {

    /** Inserts a new order (id must be null) and returns it with the generated id. */
    Order save(Order order);

    /** Scoped by user: an order of another user is reported as absent (no information leak). */
    Optional<Order> findByIdAndUserId(int orderId, int userId);

    /** All orders of the user in chronological order (datetime, then id). */
    List<Order> findByUserId(int userId);

    /** Orders of the user with any of the given statuses, chronological (FILLED = executed, NEW = pending). */
    List<Order> findByUserIdAndStatusIn(int userId, Set<OrderStatus> statuses);

    /**
     * Atomically moves the order to CANCELLED only if it belongs to the user and is still NEW
     * ({@code UPDATE ... WHERE id = ? AND userid = ? AND status = 'NEW'}).
     *
     * @return {@code true} if it was cancelled, {@code false} if no row matched
     */
    boolean cancelIfNew(int orderId, int userId);
}
