package com.cocos.challenge.orders.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderIdempotencyJpaRepository extends JpaRepository<OrderIdempotencyEntity, Long> {

    /** Backed by the unique index of (user_id, idempotency_key). */
    Optional<OrderIdempotencyEntity> findByUserIdAndIdempotencyKey(Integer userId, UUID idempotencyKey);
}
