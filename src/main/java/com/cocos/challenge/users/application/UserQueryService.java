package com.cocos.challenge.users.application;

import java.util.Optional;

import com.cocos.challenge.users.application.port.in.UserLookup;
import com.cocos.challenge.users.application.port.out.UserRepositoryPort;
import com.cocos.challenge.users.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class UserQueryService implements UserLookup {

    private final UserRepositoryPort users;

    UserQueryService(UserRepositoryPort users) {
        this.users = users;
    }

    @Override
    public Optional<User> findById(int userId) {
        return users.findById(userId);
    }
}
