package com.cocos.challenge.users.infrastructure.persistence;

import java.time.Duration;
import java.util.Optional;

import com.cocos.challenge.users.application.port.out.UserRepositoryPort;
import com.cocos.challenge.users.domain.User;
import org.springframework.stereotype.Component;

@Component
class UserPersistenceAdapter implements UserRepositoryPort {

    private final UserJpaRepository repository;

    UserPersistenceAdapter(UserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<User> findById(int userId) {
        return repository.findById(userId)
                .map(entity -> new User(entity.getId(), entity.getEmail(), entity.getAccountNumber()));
    }

    @Override
    public boolean lockById(int userId, Duration timeout) {
        repository.setLocalLockTimeout(timeout.toMillis() + "ms");
        return repository.lockById(userId).isPresent();
    }
}
