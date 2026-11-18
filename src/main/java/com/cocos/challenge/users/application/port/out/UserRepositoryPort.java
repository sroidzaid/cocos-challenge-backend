package com.cocos.challenge.users.application.port.out;

import java.time.Duration;
import java.util.Optional;

import com.cocos.challenge.users.domain.User;

public interface UserRepositoryPort {

    Optional<User> findById(int userId);

    /**
     * Locks the user's row until the end of the current transaction, waiting at most {@code timeout}.
     *
     * @return {@code false} if the row does not exist
     */
    boolean lockById(int userId, Duration timeout);
}
