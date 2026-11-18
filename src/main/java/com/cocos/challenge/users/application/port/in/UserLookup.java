package com.cocos.challenge.users.application.port.in;

import java.util.Optional;

import com.cocos.challenge.users.domain.User;

public interface UserLookup {

    Optional<User> findById(int userId);
}
