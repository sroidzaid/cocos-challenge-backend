package com.cocos.challenge.users.application.port.in;

/**
 * Per-account mutual exclusion for operations that read the user's ledger (orders table) and then write to it
 * (place order, cancel order, cash transfer).
 * <p>
 * Contract:
 * <ul>
 *     <li>must be called inside an existing transaction, as its <b>first</b> statement; the lock is held until
 *     that transaction commits or rolls back;</li>
 *     <li>the caller transaction must use READ COMMITTED, so that the balance reads that follow the lock see
 *     everything committed by the previous holder (under REPEATABLE READ the snapshot would be taken before
 *     waiting for the lock and the reads would be stale);</li>
 *     <li>it only protects writers that also take the lock (cooperative).</li>
 * </ul>
 */
public interface AccountLock {

    /**
     * Blocks until the account lock is acquired or the configured timeout expires.
     *
     * @return {@code false} if the user does not exist (nothing to lock), {@code true} once locked
     * @throws org.springframework.transaction.IllegalTransactionStateException if called without a transaction
     * @throws org.springframework.dao.PessimisticLockingFailureException if the lock is not acquired in time
     */
    boolean lock(int userId);
}
