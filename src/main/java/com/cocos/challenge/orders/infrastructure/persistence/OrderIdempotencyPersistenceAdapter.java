package com.cocos.challenge.orders.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.out.IdempotencyRecord;
import com.cocos.challenge.orders.application.port.out.OrderIdempotencyPort;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.infrastructure.persistence.PersistedDataIntegrityException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
class OrderIdempotencyPersistenceAdapter implements OrderIdempotencyPort {

    static final String UNIQUE_USER_KEY_CONSTRAINT = "uq_order_idempotency_user_key";
    static final String IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT";
    private static final String UNIQUE_VIOLATION = "23505";

    private final OrderIdempotencyJpaRepository repository;

    OrderIdempotencyPersistenceAdapter(OrderIdempotencyJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> find(int userId, UUID idempotencyKey) {
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(OrderIdempotencyPersistenceAdapter::toRecord);
    }

    /**
     * Flushes immediately so that a constraint violation surfaces here, inside the caller's transaction. Only the
     * unique (user_id, idempotency_key) violation is translated to a conflict; any other integrity error (foreign key,
     * check) propagates unchanged. Nothing is retried in this transaction: PostgreSQL has already aborted it, so the
     * exception must propagate and the whole transaction (including the order) rolls back.
     */
    @Override
    public void save(IdempotencyRecord record) {
        try {
            repository.saveAndFlush(new OrderIdempotencyEntity(record.userId(), record.idempotencyKey(),
                    record.requestHash(), record.orderId(), record.orderStatus().name(),
                    record.rejectionReason() == null ? null : record.rejectionReason().name(), record.createdAt()));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, UNIQUE_USER_KEY_CONSTRAINT)) {
                throw new ConflictException(IDEMPOTENCY_KEY_CONFLICT, "Another request with idempotency key "
                        + record.idempotencyKey() + " was processed concurrently; retry to get its result");
            }
            throw e;
        }
    }

    static boolean violates(Throwable error, String constraint) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UNIQUE_VIOLATION.equals(violation.getSQLState())
                        && constraint.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private static IdempotencyRecord toRecord(OrderIdempotencyEntity e) {
        try {
            return new IdempotencyRecord(e.getUserId(), e.getIdempotencyKey(), e.getRequestHash(), e.getOrderId(),
                    OrderStatus.valueOf(e.getOrderStatus()),
                    e.getRejectionReason() == null ? null : RejectionReason.valueOf(e.getRejectionReason()),
                    e.getCreatedAt());
        } catch (IllegalArgumentException ex) {
            throw new PersistedDataIntegrityException("order_idempotency row for key " + e.getIdempotencyKey()
                    + " cannot be interpreted: " + ex.getMessage());
        }
    }
}
