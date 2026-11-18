package com.cocos.challenge.users.application;

import java.time.Duration;

import com.cocos.challenge.users.application.port.in.AccountLock;
import com.cocos.challenge.users.application.port.out.UserRepositoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the per-account lock by locking the account holder's row in {@code users}. The parent row acts as a
 * mutex for every ledger writer of that user, which protects invariants over the <i>sum</i> of orders
 * (write skew on inserts) without any schema change. Locking existing order rows would not: new inserts are not
 * blocked by row locks.
 * <p>
 * {@link Propagation#MANDATORY}: outside a transaction the lock would be released immediately and protect nothing,
 * so calling it that way is a programming error that must fail fast.
 */
@Service
class AccountLockService implements AccountLock {

    private final UserRepositoryPort users;
    private final Duration timeout;

    AccountLockService(UserRepositoryPort users,
            @Value("${app.persistence.account-lock-timeout}") Duration timeout) {
        this.users = users;
        this.timeout = timeout;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(int userId) {
        return users.lockById(userId, timeout);
    }
}
