package com.cocos.challenge.orders.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cocos.challenge.orders.application.port.out.OrderRepositoryPort;
import com.cocos.challenge.orders.domain.Order;
import com.cocos.challenge.orders.domain.OrderStatus;
import org.springframework.stereotype.Component;

@Component
class OrderPersistenceAdapter implements OrderRepositoryPort {

    private final OrderJpaRepository repository;

    OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Order save(Order order) {
        return OrderMapper.toDomain(repository.save(OrderMapper.toNewEntity(order)));
    }

    @Override
    public Optional<Order> findByIdAndUserId(int orderId, int userId) {
        return repository.findByIdAndUserId(orderId, userId).map(OrderMapper::toDomain);
    }

    @Override
    public List<Order> findByUserId(int userId) {
        return repository.findByUserIdOrderByDatetimeAscIdAsc(userId).stream().map(OrderMapper::toDomain).toList();
    }

    @Override
    public List<Order> findByUserIdAndStatusIn(int userId, Set<OrderStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        List<String> raw = statuses.stream().map(Enum::name).toList();
        return repository.findByUserIdAndStatusInOrderByDatetimeAscIdAsc(userId, raw).stream()
                .map(OrderMapper::toDomain)
                .toList();
    }

    @Override
    public boolean cancelIfNew(int orderId, int userId) {
        return repository.cancelIfNew(orderId, userId) == 1;
    }
}
