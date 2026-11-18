package com.cocos.challenge.shared.infrastructure.persistence;

/**
 * Data read from the database does not satisfy what the application relies on (e.g. an unknown order side,
 * a FILLED order without price, two instruments with the same ticker).
 * <p>
 * The provided schema has no CHECK/UNIQUE constraints, so such rows are possible. Failing loudly is preferred
 * over silently ignoring them, which could produce wrong balances. Messages contain identifiers only, never
 * personal or credential data. Surfaces as a generic 500 to clients and is logged with full detail.
 */
public class PersistedDataIntegrityException extends RuntimeException {

    public PersistedDataIntegrityException(String message) {
        super(message);
    }
}
