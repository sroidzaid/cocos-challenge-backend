package com.cocos.challenge.users.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserEntity, Integer> {

    /**
     * Sets {@code lock_timeout} for the current transaction only ({@code is_local = true}).
     * Explicit SQL instead of a JPA lock-timeout hint: deterministic on PostgreSQL, independent of dialect support.
     */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);

    /**
     * {@code FOR NO KEY UPDATE}: conflicts with itself (mutual exclusion between ledger writers of the same user)
     * but not with the {@code FOR KEY SHARE} locks taken by foreign-key checks, so inserting orders that reference
     * the user is not blocked unnecessarily.
     */
    @Query(value = "SELECT id FROM users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Integer> lockById(@Param("id") int id);
}
