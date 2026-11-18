package com.cocos.challenge.users.domain;

/** Account holder. Mirrors the {@code users} table; email and account number are nullable in the provided schema. */
public record User(int id, String email, String accountNumber) {
}
