package com.cocos.challenge.portfolio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.cocos.challenge.orders.application.port.in.LedgerQuery;
import com.cocos.challenge.portfolio.application.port.in.GetPortfolioUseCase;
import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.support.ApplicationIT;
import com.cocos.challenge.users.application.port.in.AccountLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

/**
 * The portfolio reads run in one read-only REPEATABLE READ transaction (one PostgreSQL snapshot), and that read
 * consistency does not take locks: it neither waits for nor blocks the account lock used by order placement.
 */
@ApplicationIT
class PortfolioTransactionIT {

    @Autowired
    private GetPortfolioUseCase getPortfolio;

    @MockitoSpyBean
    private LedgerQuery ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountLock accountLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void readsRunInAReadOnlyRepeatableReadTransactionInPostgres() {
        AtomicReference<String> isolation = new AtomicReference<>();
        AtomicReference<String> readOnly = new AtomicReference<>();
        doAnswer(invocation -> {
            // Same connection as the JPA transaction: this is what PostgreSQL actually applies.
            isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
            readOnly.set(jdbc.queryForObject("SHOW transaction_read_only", String.class));
            return invocation.callRealMethod();
        }).when(ledger).cashBalance(anyInt());

        getPortfolio.getPortfolio(1);

        assertThat(isolation).hasValue("repeatable read");
        assertThat(readOnly).hasValue("on");
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                .as("the pooled connection is restored afterwards")
                .isEqualTo("read committed");
    }

    @Test
    void portfolioDoesNotWaitForTheAccountLock() throws Exception {
        TransactionTemplate readCommitted = new TransactionTemplate(transactionManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> readCommitted.executeWithoutResult(status -> {
            assertThat(accountLock.lock(1)).isTrue();
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();

            Portfolio portfolio = getPortfolio.getPortfolio(1);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .as("served while another transaction holds the account lock")
                    .isLessThan(2000L);
            assertThat(portfolio.totalAccountValue()).isEqualByComparingTo("889756.00");
        } finally {
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
        }
    }
}
