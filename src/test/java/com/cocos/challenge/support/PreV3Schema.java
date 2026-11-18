package com.cocos.challenge.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Simulates a database where V3 (orders integrity constraints) has not been applied, e.g. the external Cocos database,
 * so that tests can still prove the application's own defenses against rows that V3 would reject.
 * <p>
 * PostgreSQL DDL is transactional: the constraint is dropped inside the current test transaction and restored by its
 * rollback. Only usable in tests that roll back ({@link PersistenceIT}); it refuses to run without a transaction so
 * the shared schema can never be altered by mistake.
 */
public final class PreV3Schema {

    private PreV3Schema() {
    }

    public static void dropCheck(JdbcTemplate jdbc, String constraint) {
        requireTransaction();
        jdbc.execute("ALTER TABLE orders DROP CONSTRAINT " + constraint);
    }

    public static void dropNotNull(JdbcTemplate jdbc, String column) {
        requireTransaction();
        jdbc.execute("ALTER TABLE orders ALTER COLUMN " + column + " DROP NOT NULL");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Only inside a rolled-back test transaction (@PersistenceIT)");
        }
    }
}
