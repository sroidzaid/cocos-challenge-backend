package com.cocos.challenge.orders.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Persistent idempotency keys of order creation, scoped by user. */
public interface OrderIdempotencyPort {

    Optional<IdempotencyRecord> find(int userId, UUID idempotencyKey);

    /**
     * Inserts the record in the current transaction (together with its order).
     *
     * @throws com.cocos.challenge.shared.domain.ConflictException IDEMPOTENCY_KEY_CONFLICT if the database rejects it
     *         because of the unique (user, key) constraint, i.e. another transaction stored the same key first. The
     *         current transaction must then roll back (PostgreSQL aborts it); nothing else is caught or retried.
     */
    void save(IdempotencyRecord record);
}
