package com.cocos.challenge.orders.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cocos.challenge.orders.application.port.out.IdempotencyRecord;
import com.cocos.challenge.orders.domain.OrderStatus;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.support.PersistenceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against the real table created by db/migration/V2 (rolled back after each test). Only the unique (user, key)
 * violation becomes an idempotency conflict; every other integrity error propagates unchanged. Each failing case is
 * the last statement of its test, because PostgreSQL aborts the transaction after an error.
 */
@PersistenceIT
@Import(OrderIdempotencyPersistenceAdapter.class)
class OrderIdempotencyPersistenceAdapterIT {

    private static final String HASH = "a".repeat(64);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 12, 10, 15);

    @Autowired
    private OrderIdempotencyPersistenceAdapter adapter;

    @Test
    void storesAndFindsTheCreationSnapshotByUserAndKey() {
        UUID key = UUID.randomUUID();
        IdempotencyRecord rejected = new IdempotencyRecord(1, key, HASH, 6, OrderStatus.REJECTED,
                RejectionReason.INSUFFICIENT_HOLDINGS, AT);

        adapter.save(rejected);

        assertThat(adapter.find(1, key)).contains(rejected);
        assertThat(adapter.find(2, key)).as("keys are scoped by user").isEmpty();
        assertThat(adapter.find(1, UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSameKeyCanBeUsedByDifferentUsers() {
        UUID key = UUID.randomUUID();

        adapter.save(new IdempotencyRecord(1, key, HASH, 1, OrderStatus.FILLED, null, AT));
        adapter.save(new IdempotencyRecord(2, key, HASH, 2, OrderStatus.FILLED, null, AT));

        assertThat(adapter.find(1, key)).map(IdempotencyRecord::orderId).contains(1);
        assertThat(adapter.find(2, key)).map(IdempotencyRecord::orderId).contains(2);
    }

    @Test
    void duplicatedUserAndKeyIsAnIdempotencyConflict() {
        UUID key = UUID.randomUUID();
        adapter.save(new IdempotencyRecord(1, key, HASH, 1, OrderStatus.FILLED, null, AT));

        assertThatThrownBy(() -> adapter.save(new IdempotencyRecord(1, key, HASH, 2, OrderStatus.FILLED, null, AT)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void foreignKeyViolationIsNotAnIdempotencyConflict() {
        assertThatThrownBy(() -> adapter.save(
                new IdempotencyRecord(1, UUID.randomUUID(), HASH, 9999, OrderStatus.FILLED, null, AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }

    @Test
    void anotherUniqueConstraintIsNotAnIdempotencyConflict() {
        adapter.save(new IdempotencyRecord(1, UUID.randomUUID(), HASH, 1, OrderStatus.FILLED, null, AT));

        // Same order with another key violates uq_order_idempotency_order, not the (user, key) constraint.
        assertThatThrownBy(() -> adapter.save(
                new IdempotencyRecord(1, UUID.randomUUID(), HASH, 1, OrderStatus.FILLED, null, AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }

    @Test
    void checkConstraintViolationIsNotAnIdempotencyConflict() {
        assertThatThrownBy(() -> adapter.save(
                new IdempotencyRecord(1, UUID.randomUUID(), "not-a-sha256", 1, OrderStatus.FILLED, null, AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }
}
