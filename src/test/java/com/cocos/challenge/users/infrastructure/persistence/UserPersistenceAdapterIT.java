package com.cocos.challenge.users.infrastructure.persistence;

import com.cocos.challenge.support.PersistenceIT;
import com.cocos.challenge.users.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@PersistenceIT
@Import(UserPersistenceAdapter.class)
class UserPersistenceAdapterIT {

    @Autowired
    private UserPersistenceAdapter adapter;

    @Test
    void readsSeedUsersMappingLowercaseColumns() {
        assertThat(adapter.findById(1)).contains(new User(1, "emiliano@test.com", "10001"));
        assertThat(adapter.findById(4)).contains(new User(4, "juan@test.com", "10004"));
    }

    @Test
    void unknownUserIsAbsent() {
        assertThat(adapter.findById(999)).isEmpty();
    }
}
