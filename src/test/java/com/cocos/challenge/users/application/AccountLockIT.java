package com.cocos.challenge.users.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency behaviour of the per-account lock with real, independent transactions on separate threads.
 * Nothing is written to the database: the lock is a {@code SELECT ... FOR NO KEY UPDATE} released on commit.
 */
@ApplicationIT
@TestPropertySource(properties = "app.persistence.account-lock-timeout=1s")
class AccountLockIT {

    private static final int USER_1 = 1;
    private static final int USER_2 = 2;

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private TransactionTemplate readCommitted;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        readCommitted = new TransactionTemplate(transactionManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void secondTransactionWaitsUntilTheFirstOneCommits() throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> holdLock(USER_1, firstHoldsLock, releaseFirst));
        assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Boolean> second = executor.submit(() -> readCommitted.execute(status -> accountLock.lock(USER_1)));

        assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                .as("second transaction must be blocked while the first holds the lock")
                .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();
        assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void waitingLongerThanTheTimeoutFailsWithATransientLockingError() throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> holdLock(USER_1, firstHoldsLock, releaseFirst));
        assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            long start = System.nanoTime();

            assertThatThrownBy(() -> readCommitted.execute(status -> accountLock.lock(USER_1)))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(900L, 4000L);
        } finally {
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void locksOfDifferentAccountsDoNotBlockEachOther() throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> holdLock(USER_1, firstHoldsLock, releaseFirst));
        assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            Future<Boolean> other = executor.submit(() -> readCommitted.execute(status -> accountLock.lock(USER_2)));

            assertThat(other.get(500, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unknownUserCannotBeLocked() {
        Boolean locked = readCommitted.execute(status -> accountLock.lock(999));

        assertThat(locked).isFalse();
    }

    @Test
    void lockingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> accountLock.lock(USER_1)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void holdLock(int userId, CountDownLatch locked, CountDownLatch release) {
        readCommitted.executeWithoutResult(status -> {
            assertThat(accountLock.lock(userId)).isTrue();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
